/*
 * Copyright 2007-2010 Bas Leijdekkers
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
package com.intellij.java.impl.ig.fixes;

import com.intellij.java.impl.ig.psiutils.FinalUtils;
import com.intellij.java.language.psi.PsiField;
import com.intellij.java.language.psi.PsiModifier;
import com.intellij.java.language.psi.PsiModifierList;
import com.intellij.java.language.psi.PsiReferenceExpression;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.psi.PsiElement;
import consulo.language.util.IncorrectOperationException;
import consulo.project.Project;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class MakeFieldFinalFix extends InspectionGadgetsFix {

  private final String fieldName;

  private MakeFieldFinalFix(String fieldName) {
    this.fieldName = fieldName;
  }

  @Nullable
  public static InspectionGadgetsFix buildFix(PsiField field) {
    if (!FinalUtils.canBeFinal(field)) {
      return null;
    }
    final String name = field.getName();
    return new MakeFieldFinalFix(name);
  }

  @Nonnull
  public static InspectionGadgetsFix buildFixUnconditional(PsiField field) {
    return new MakeFieldFinalFix(field.getName());
  }

  @Nonnull
  public String getName() {
    return InspectionGadgetsLocalize.makeFieldFinalQuickfix(fieldName).get();
  }

  @Override
  protected void doFix(Project project, ProblemDescriptor descriptor)
    throws IncorrectOperationException {
    final PsiElement element = descriptor.getPsiElement();
    final PsiField field;
    if (element instanceof PsiReferenceExpression) {
      final PsiReferenceExpression referenceExpression =
        (PsiReferenceExpression)element;
      final PsiElement target = referenceExpression.resolve();
      if (!(target instanceof PsiField)) {
        return;
      }
      field = (PsiField)target;
    }
    else {
      final PsiElement parent = element.getParent();
      if (!(parent instanceof PsiField)) {
        return;
      }
      field = (PsiField)parent;
    }
    final PsiModifierList modifierList = field.getModifierList();
    if (modifierList == null) {
      return;
    }
    modifierList.setModifierProperty(PsiModifier.VOLATILE, false);
    modifierList.setModifierProperty(PsiModifier.FINAL, true);
  }
}