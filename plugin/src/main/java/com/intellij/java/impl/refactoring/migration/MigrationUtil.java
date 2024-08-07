/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.migration;

import com.intellij.java.impl.psi.impl.source.resolve.reference.impl.providers.JavaClassReference;
import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiJavaCodeReferenceElement;
import com.intellij.java.language.psi.PsiMigration;
import consulo.application.WriteAction;
import consulo.content.scope.SearchScope;
import consulo.document.util.TextRange;
import consulo.language.psi.*;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.search.ReferencesSearch;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.usage.UsageInfo;
import consulo.util.lang.Comparing;

import java.util.ArrayList;

public class MigrationUtil {
  private static final Logger LOG = Logger.getInstance(MigrationUtil.class);

  private MigrationUtil() {
  }

  public static UsageInfo[] findPackageUsages(Project project, PsiMigration migration, String qName, SearchScope searchScope) {
    PsiPackage aPackage = findOrCreatePackage(project, migration, qName);

    return findRefs(aPackage, searchScope);
  }

  private static PsiElement bindNonJavaReference(PsiElement bindTo, PsiElement element, UsageInfo usage) {
    final TextRange range = usage.getRangeInElement();
    for (PsiReference reference : element.getReferences()) {
      if (reference instanceof JavaClassReference) {
        final JavaClassReference classReference = (JavaClassReference) reference;
        if (classReference.getRangeInElement().equals(range)) {
          return classReference.bindToElement(bindTo);
        }
      }
    }
    return bindTo;
  }

  public static UsageInfo[] findClassUsages(Project project, PsiMigration migration, String qName, SearchScope searchScope) {
    PsiClass aClass = findOrCreateClass(project, migration, qName);

    return findRefs(aClass, searchScope);
  }

  private static UsageInfo[] findRefs(final PsiElement aClass, SearchScope searchScope) {
    final ArrayList<UsageInfo> results = new ArrayList<>();
    for (PsiReference usage : ReferencesSearch.search(aClass, searchScope, false)) {
      results.add(new UsageInfo(usage));
    }

    return results.toArray(UsageInfo.EMPTY_ARRAY);
  }

  static void doMigration(PsiElement elementToBind, String newQName, UsageInfo[] usages, ArrayList<SmartPsiElementPointer<PsiElement>> refsToShorten) {
    try {
      SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(elementToBind.getProject());
      // rename all references
      for (UsageInfo usage : usages) {
        if (usage instanceof MigrationProcessor.MigrationUsageInfo) {
          final MigrationProcessor.MigrationUsageInfo usageInfo = (MigrationProcessor.MigrationUsageInfo) usage;
          if (Comparing.equal(newQName, usageInfo.mapEntry.getNewName())) {
            PsiElement element = usage.getElement();
            if (element == null || !element.isValid()) {
              continue;
            }
            PsiElement psiElement;
            if (element instanceof PsiJavaCodeReferenceElement) {
              psiElement = ((PsiJavaCodeReferenceElement) element).bindToElement(elementToBind);
            } else {
              psiElement = bindNonJavaReference(elementToBind, element, usage);
            }
            if (psiElement != null) {
              refsToShorten.add(smartPointerManager.createSmartPsiElementPointer(psiElement));
            }
          }
        }
      }
    } catch (IncorrectOperationException e) {
      // should not happen!
      LOG.error(e);
    }
  }

  static PsiPackage findOrCreatePackage(Project project, final PsiMigration migration, final String qName) {
    PsiPackage aPackage = JavaPsiFacade.getInstance(project).findPackage(qName);
    if (aPackage != null) {
      return aPackage;
    } else {
      return WriteAction.compute(() -> migration.createPackage(qName));
    }
  }

  static PsiClass findOrCreateClass(Project project, final PsiMigration migration, final String qName) {
    PsiClass aClass = JavaPsiFacade.getInstance(project).findClass(qName, GlobalSearchScope.allScope(project));
    if (aClass == null) {
      aClass = WriteAction.compute(() -> migration.createClass(qName));
    }
    return aClass;
  }
}
