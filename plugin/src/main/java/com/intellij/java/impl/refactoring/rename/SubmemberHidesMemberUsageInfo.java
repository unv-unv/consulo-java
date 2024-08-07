/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: dsl
 * Date: 05.06.2002
 * Time: 12:43:27
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.java.impl.refactoring.rename;

import com.intellij.java.language.psi.PsiMethod;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.rename.UnresolvableCollisionUsageInfo;
import consulo.language.editor.refactoring.ui.RefactoringUIUtil;
import consulo.language.editor.refactoring.util.CommonRefactoringUtil;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import consulo.usage.UsageViewUtil;

public class SubmemberHidesMemberUsageInfo extends UnresolvableCollisionUsageInfo {
  public SubmemberHidesMemberUsageInfo(PsiElement element, PsiElement referencedElement) {
    super(element, referencedElement);
  }

  public String getDescription() {
    PsiElement element = getElement();
    LocalizeValue descr = element instanceof PsiMethod
      ? RefactoringLocalize.zeroWillOverrideRenamed1(
        RefactoringUIUtil.getDescription(element, true),
        UsageViewUtil.getType(element)
      )
      : RefactoringLocalize.zeroWillHideRenamed1(
        RefactoringUIUtil.getDescription(element, true),
        UsageViewUtil.getType(element)
      );
    return CommonRefactoringUtil.capitalize(descr.get());
  }
}
