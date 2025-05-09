/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.java.language.impl.psi.impl;

import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.InheritanceUtil;
import com.intellij.java.language.psi.util.JavaClassSupers;
import consulo.application.progress.ProgressIndicatorProvider;
import consulo.language.psi.PsiCompiledElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiManager;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.logging.Logger;
import consulo.util.lang.Comparing;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

public class InheritanceImplUtil {
  private static final Logger LOG = Logger.getInstance(InheritanceImplUtil.class);

  public static boolean isInheritor(@Nonnull final PsiClass candidateClass, @Nonnull PsiClass baseClass, final boolean checkDeep) {
    if (baseClass instanceof PsiAnonymousClass || baseClass.getManager().areElementsEquivalent(baseClass, candidateClass)) {
      return false;
    }
    if (!checkDeep) {
      return isInheritor(candidateClass.getManager(), candidateClass, baseClass, false, null);
    }

    GlobalSearchScope scope = candidateClass.getResolveScope();
    return JavaClassSupers.getInstance().getSuperClassSubstitutor(baseClass, candidateClass, scope, PsiSubstitutor.EMPTY) != null;
  }

  static boolean hasObjectQualifiedName(@Nonnull PsiClass candidateClass) {
    if (!CommonClassNames.JAVA_LANG_OBJECT_SHORT.equals(candidateClass.getName())) {
      return false;
    }
    PsiElement parent = candidateClass.getParent();
    return parent instanceof PsiJavaFile && CommonClassNames.DEFAULT_PACKAGE.equals(((PsiJavaFile) parent).getPackageName());
  }

  private static boolean isInheritor(@Nonnull PsiManager manager, @Nonnull PsiClass candidateClass, @Nonnull PsiClass baseClass, boolean checkDeep, @Nullable Set<PsiClass> checkedClasses) {
    if (candidateClass instanceof PsiAnonymousClass) {
      final PsiClass baseCandidateClass = ((PsiAnonymousClass) candidateClass).getBaseClassType().resolve();
      if (baseCandidateClass != null) {
        if (!checkDeep) {
          return manager.areElementsEquivalent(baseCandidateClass, baseClass);
        }
        return InheritanceUtil.isInheritorOrSelf(baseCandidateClass, baseClass, true);
      }
      return false;
    }
    if (checkDeep && LOG.isDebugEnabled()) {
      LOG.debug("Using uncached version for " + candidateClass.getQualifiedName() + " and " + baseClass);
    }

    JavaPsiFacade facade = JavaPsiFacade.getInstance(manager.getProject());
    if (hasObjectQualifiedName(baseClass)) {
      PsiClass objectClass = facade.findClass(CommonClassNames.JAVA_LANG_OBJECT, candidateClass.getResolveScope());
      if (manager.areElementsEquivalent(baseClass, objectClass)) {
        if (manager.areElementsEquivalent(candidateClass, objectClass)) {
          return false;
        }
        if (checkDeep || candidateClass.isInterface()) {
          return true;
        }
        return manager.areElementsEquivalent(candidateClass.getSuperClass(), objectClass);
      }
    }

    if (!checkDeep) {
      if (candidateClass instanceof PsiCompiledElement) {
        String baseQName = baseClass.getQualifiedName();
        if (baseQName == null) {
          return false;
        }

        if (CommonClassNames.JAVA_LANG_ENUM.equals(baseQName) && candidateClass.isEnum()) {
          return facade.findClass(baseQName, candidateClass.getResolveScope()) != null;
        }
        if (CommonClassNames.JAVA_LANG_ANNOTATION_ANNOTATION.equals(baseQName) && candidateClass.isAnnotationType()) {
          return facade.findClass(baseQName, candidateClass.getResolveScope()) != null;
        }

        boolean isCandidateInterface = candidateClass.isInterface();
        boolean isBaseInterface = baseClass.isInterface();

        if (isCandidateInterface == isBaseInterface && checkReferenceListWithQualifiedNamesInClsClass(baseQName, candidateClass.getExtendsList(), facade)) {
          return true;
        }
        return isBaseInterface && !isCandidateInterface &&
            checkReferenceListWithQualifiedNamesInClsClass(baseQName, candidateClass.getImplementsList(), facade);
      }
      boolean isCandidateInterface = candidateClass.isInterface();
      boolean isBaseInterface = baseClass.isInterface();
      String baseName = baseClass.getName();
      if (isCandidateInterface == isBaseInterface) {
        return PsiClassImplUtil.isInExtendsList(candidateClass, baseClass, baseName, manager);
      }
      if (!isCandidateInterface) {
        return PsiClassImplUtil.isInReferenceList(candidateClass.getImplementsList(), baseClass, baseName, manager);
      }

      return false;
    }

    return isInheritorWithoutCaching(manager, candidateClass, baseClass, checkedClasses);
  }

  private static boolean checkReferenceListWithQualifiedNamesInClsClass(@Nonnull final String baseQName, @Nullable final PsiReferenceList extList, @Nonnull JavaPsiFacade facade) {
    if (extList != null) {
      // in Cls class it's fast
      PsiJavaCodeReferenceElement[] referenceElements = extList.getReferenceElements();
      if (referenceElements.length != 0) {
        GlobalSearchScope scope = extList.getResolveScope();
        for (PsiJavaCodeReferenceElement ref : referenceElements) {
          if (Comparing.equal(PsiNameHelper.getQualifiedClassName(ref.getQualifiedName(), false), baseQName) && facade.findClass(baseQName, scope) != null) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isInheritorWithoutCaching(@Nonnull PsiManager manager, @Nonnull PsiClass aClass, @Nonnull PsiClass baseClass, @Nullable Set<PsiClass> checkedClasses) {
    if (manager.areElementsEquivalent(aClass, baseClass)) {
      return false;
    }

    if (aClass.isInterface() && !baseClass.isInterface()) {
      return false;
    }

    if (checkedClasses == null) {
      checkedClasses = new HashSet<PsiClass>();
    }
    checkedClasses.add(aClass);

    return checkInheritor(manager, aClass.getExtendsListTypes(), baseClass, checkedClasses) || checkInheritor(manager, aClass.getImplementsListTypes(), baseClass, checkedClasses);
  }

  private static boolean checkInheritor(@Nonnull PsiManager manager, @Nonnull PsiClassType[] supers, @Nonnull PsiClass baseClass, @Nonnull Set<PsiClass> checkedClasses) {
    for (PsiClassType aSuper : supers) {
      PsiClass aClass = aSuper.resolve();
      if (aClass != null && checkInheritor(manager, aClass, baseClass, checkedClasses)) {
        return true;
      }
    }
    return false;
  }

  private static boolean checkInheritor(@Nonnull PsiManager manager, @Nonnull PsiClass aClass, @Nonnull PsiClass baseClass, @Nonnull Set<PsiClass> checkedClasses) {
    ProgressIndicatorProvider.checkCanceled();
    if (manager.areElementsEquivalent(baseClass, aClass)) {
      return true;
    }
    if (checkedClasses.contains(aClass)) { // to prevent infinite recursion
      return false;
    }
    return isInheritor(manager, aClass, baseClass, true, checkedClasses);
  }

  public static boolean isInheritorDeep(@Nonnull PsiClass candidateClass, @Nonnull PsiClass baseClass, @Nullable final PsiClass classToByPass) {
    if (baseClass instanceof PsiAnonymousClass) {
      return false;
    }

    Set<PsiClass> checkedClasses = null;
    if (classToByPass != null) {
      checkedClasses = new HashSet<PsiClass>();
      checkedClasses.add(classToByPass);
    }
    return isInheritor(candidateClass.getManager(), candidateClass, baseClass, true, checkedClasses);
  }
}
