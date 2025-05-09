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
package com.intellij.java.language.impl.psi.impl.source.resolve;

import com.intellij.java.language.impl.psi.scope.ElementClassHint;
import com.intellij.java.language.impl.psi.scope.NameHint;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.infos.CandidateInfo;
import com.intellij.java.language.psi.infos.ClassCandidateInfo;
import com.intellij.java.language.psi.scope.JavaScopeProcessorEvent;
import consulo.language.psi.FileContextUtil;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.resolve.BaseScopeProcessor;
import consulo.language.psi.resolve.PsiScopeProcessor;
import consulo.language.psi.resolve.ResolveState;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.logging.Logger;
import consulo.util.collection.SmartList;
import consulo.util.dataholder.Key;
import consulo.util.lang.Comparing;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;

import java.util.Iterator;
import java.util.List;

public class ClassResolverProcessor extends BaseScopeProcessor implements NameHint, ElementClassHint {
  private static final Logger LOG = Logger.getInstance(ClassResolverProcessor.class);
  private static final String[] DEFAULT_PACKAGES = {CommonClassNames.DEFAULT_PACKAGE};

  private final String myClassName;
  @Nonnull
  private final PsiFile myContainingFile;
  private final PsiElement myPlace;
  private final PsiResolveHelper myResolveHelper;
  private PsiClass myAccessClass = null;
  private List<ClassCandidateInfo> myCandidates = null;
  private boolean myHasAccessibleCandidate;
  private boolean myHasInaccessibleCandidate;
  private JavaResolveResult[] myResult = JavaResolveResult.EMPTY_ARRAY;
  private PsiElement myCurrentFileContext;

  public ClassResolverProcessor(@Nonnull String className, @Nonnull PsiElement startPlace, @Nonnull PsiFile containingFile) {
    myClassName = className;
    myContainingFile = containingFile;
    PsiElement place = containingFile instanceof JavaCodeFragment && ((JavaCodeFragment) containingFile).getVisibilityChecker() != null ? null : startPlace;
    myPlace = place;
    if (place instanceof PsiJavaCodeReferenceElement) {
      final PsiJavaCodeReferenceElement expression = (PsiJavaCodeReferenceElement) place;
      final PsiElement qualifierExpression = expression.getQualifier();
      if (qualifierExpression instanceof PsiExpression) {
        final PsiType type = ((PsiExpression) qualifierExpression).getType();
        if (type instanceof PsiClassType) {
          myAccessClass = ((PsiClassType) type).resolve();
        }
      } else if (qualifierExpression instanceof PsiJavaCodeReferenceElement) {
        LOG.assertTrue(qualifierExpression.isValid());
        final PsiElement resolve = ((PsiJavaCodeReferenceElement) qualifierExpression).resolve();
        if (resolve instanceof PsiClass) {
          myAccessClass = (PsiClass) resolve;
        }
      }
    }
    myResolveHelper = JavaPsiFacade.getInstance(containingFile.getProject()).getResolveHelper();
  }

  @Nonnull
  public JavaResolveResult[] getResult() {
    if (myResult != null) {
      return myResult;
    }
    if (myCandidates == null) {
      return myResult = JavaResolveResult.EMPTY_ARRAY;
    }
    if (myHasAccessibleCandidate && myHasInaccessibleCandidate) {
      for (Iterator<ClassCandidateInfo> iterator = myCandidates.iterator(); iterator.hasNext(); ) {
        CandidateInfo info = iterator.next();
        if (!info.isAccessible()) {
          iterator.remove();
        }
      }
      myHasInaccessibleCandidate = false;
    }

    myResult = myCandidates.toArray(new JavaResolveResult[myCandidates.size()]);
    return myResult;
  }

  @Override
  public String getName(@Nonnull ResolveState state) {
    return myClassName;
  }

  @Override
  public boolean shouldProcess(DeclarationKind kind) {
    return kind == DeclarationKind.CLASS;
  }

  @Override
  public void handleEvent(@Nonnull PsiScopeProcessor.Event event, Object associated) {
    if (event == JavaScopeProcessorEvent.SET_CURRENT_FILE_CONTEXT) {
      myCurrentFileContext = (PsiElement) associated;
    }
  }

  private static boolean isImported(PsiElement fileContext) {
    return fileContext instanceof PsiImportStatementBase;
  }

  private boolean isOnDemand(PsiElement fileContext, PsiClass psiClass) {
    if (isImported(fileContext)) {
      return ((PsiImportStatementBase) fileContext).isOnDemand();
    }

    String fqn = psiClass.getQualifiedName();
    if (fqn == null) {
      return false;
    }

    PsiFile file = myPlace == null ? null : FileContextUtil.getContextFile(myContainingFile);

    String[] defaultPackages = file instanceof PsiJavaFile ? ((PsiJavaFile) file).getImplicitlyImportedPackages() : DEFAULT_PACKAGES;
    String packageName = StringUtil.getPackageName(fqn);
    for (String defaultPackage : defaultPackages) {
      if (defaultPackage.equals(packageName)) {
        return true;
      }
    }

    // class from my package imported implicitly
    return file instanceof PsiJavaFile && ((PsiJavaFile) file).getPackageName().equals(packageName);
  }

  private Domination dominates(@Nonnull PsiClass aClass, boolean accessible, @Nonnull String fqName, @Nonnull ClassCandidateInfo info) {
    final PsiClass otherClass = info.getElement();
    String otherQName = otherClass.getQualifiedName();
    if (fqName.equals(otherQName)) {
      return Domination.DOMINATED_BY;
    }

    final PsiClass containingClass1 = aClass.getContainingClass();
    final PsiClass containingClass2 = otherClass.getContainingClass();
    if (myAccessClass != null && !Comparing.equal(containingClass1, containingClass2)) {
      if (myAccessClass.equals(containingClass1)) {
        return Domination.DOMINATES;
      }
      if (myAccessClass.equals(containingClass2)) {
        return Domination.DOMINATED_BY;
      }
    }

    //JLS 8.5:
    //A class may inherit two or more type declarations with the same name, either from two interfaces or from its superclass and an interface.
    //It is a compile-time error to attempt to refer to any ambiguously inherited class or interface by its simple name.
    if (containingClass1 != null && containingClass2 != null && containingClass2.isInheritor(containingClass1, true) &&
        !isImported(myCurrentFileContext)) {
      if (!isAmbiguousInherited(containingClass1)) {
        // shadowing
        return Domination.DOMINATED_BY;
      }
    }

    boolean infoAccessible = info.isAccessible() && isAccessible(otherClass);
    if (infoAccessible && !accessible) {
      return Domination.DOMINATED_BY;
    }
    if (!infoAccessible && accessible) {
      return Domination.DOMINATES;
    }

    // everything wins over class from default package
    boolean isDefault = StringUtil.getPackageName(fqName).isEmpty();
    boolean otherDefault = otherQName != null && StringUtil.getPackageName(otherQName).isEmpty();
    if (isDefault && !otherDefault) {
      return Domination.DOMINATED_BY;
    }
    if (!isDefault && otherDefault) {
      return Domination.DOMINATES;
    }

    // single import wins over on-demand
    boolean myOnDemand = isOnDemand(myCurrentFileContext, aClass);
    boolean otherOnDemand = isOnDemand(info.getCurrentFileResolveScope(), otherClass);
    if (myOnDemand && !otherOnDemand) {
      return Domination.DOMINATED_BY;
    }
    if (!myOnDemand && otherOnDemand) {
      return Domination.DOMINATES;
    }

    return Domination.EQUAL;
  }

  private boolean isAccessible(PsiClass otherClass) {
    if (otherClass.hasModifierProperty(PsiModifier.PRIVATE)) {
      final PsiClass containingClass = otherClass.getContainingClass();
      PsiClass containingPlaceClass = PsiTreeUtil.getParentOfType(myPlace, PsiClass.class, false);
      while (containingPlaceClass != null) {
        if (containingClass == containingPlaceClass) {
          return true;
        }
        containingPlaceClass = PsiTreeUtil.getParentOfType(containingPlaceClass, PsiClass.class);
      }
      return false;
    }
    return true;
  }

  private boolean isAmbiguousInherited(PsiClass containingClass1) {
    PsiClass psiClass = PsiTreeUtil.getParentOfType(myPlace, PsiClass.class);
    while (psiClass != null) {
      if (psiClass.isInheritor(containingClass1, false)) {
        return true;
      }
      psiClass = psiClass.getContainingClass();
    }
    return false;
  }

  @Override
  public boolean execute(@Nonnull PsiElement element, @Nonnull ResolveState state) {
    if (!(element instanceof PsiClass)) {
      return true;
    }
    final PsiClass aClass = (PsiClass) element;
    final String name = aClass.getName();
    if (!myClassName.equals(name)) {
      return true;
    }
    boolean accessible = myPlace == null || checkAccessibility(aClass);
    if (myCandidates == null) {
      myCandidates = new SmartList<ClassCandidateInfo>();
    } else {
      String fqName = aClass.getQualifiedName();
      if (fqName != null) {
        for (int i = myCandidates.size() - 1; i >= 0; i--) {
          ClassCandidateInfo info = myCandidates.get(i);

          Domination domination = dominates(aClass, accessible && isAccessible(aClass), fqName, info);
          if (domination == Domination.DOMINATED_BY) {
            return true;
          } else if (domination == Domination.DOMINATES) {
            myCandidates.remove(i);
          }
        }
      }
    }

    myHasAccessibleCandidate |= accessible;
    myHasInaccessibleCandidate |= !accessible;
    myCandidates.add(new ClassCandidateInfo(aClass, state.get(PsiSubstitutor.KEY), !accessible, myCurrentFileContext));
    myResult = null;
    if (!accessible) {
      return true;
    }
    if (aClass.hasModifierProperty(PsiModifier.PRIVATE)) {
      final PsiClass containingPlaceClass = PsiTreeUtil.getParentOfType(myPlace, PsiClass.class, false);
      if (containingPlaceClass != null && !PsiTreeUtil.isAncestor(containingPlaceClass, aClass, false)) {
        return true;
      }
    }
    return myCurrentFileContext instanceof PsiImportStatementBase;
  }

  private boolean checkAccessibility(final PsiClass aClass) {
    return myResolveHelper.isAccessible(aClass, myPlace, myAccessClass);
  }

  @Override
  public <T> T getHint(@Nonnull Key<T> hintKey) {
    if (hintKey == ElementClassHint.KEY || hintKey == NameHint.KEY) {
      @SuppressWarnings("unchecked") T t = (T) this;
      return t;
    }
    return super.getHint(hintKey);
  }
}
