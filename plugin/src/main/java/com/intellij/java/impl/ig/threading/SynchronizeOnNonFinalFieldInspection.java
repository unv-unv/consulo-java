/*
 * Copyright 2003-2010 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.threading;

import com.intellij.java.impl.ig.fixes.MakeFieldFinalFix;
import com.intellij.java.language.psi.*;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiReference;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

@ExtensionImpl
public class SynchronizeOnNonFinalFieldInspection extends BaseInspection {

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.synchronizeOnNonFinalFieldDisplayName().get();
  }

  @Override
  @Nonnull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.synchronizeOnNonFinalFieldProblemDescriptor().get();
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  @Nullable
  protected InspectionGadgetsFix buildFix(Object... infos) {
    final PsiField field = (PsiField)infos[0];
    return MakeFieldFinalFix.buildFix(field);
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SynchronizeOnNonFinalFieldVisitor();
  }

  private static class SynchronizeOnNonFinalFieldVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitSynchronizedStatement(
      @Nonnull PsiSynchronizedStatement statement) {
      super.visitSynchronizedStatement(statement);
      final PsiExpression lockExpression = statement.getLockExpression();
      if (!(lockExpression instanceof PsiReferenceExpression)) {
        return;
      }
      final PsiReference reference = lockExpression.getReference();
      if (reference == null) {
        return;
      }
      final PsiElement element = reference.resolve();
      if (!(element instanceof PsiField)) {
        return;
      }
      final PsiField field = (PsiField)element;
      if (field.hasModifierProperty(PsiModifier.FINAL)) {
        return;
      }
      registerError(lockExpression, field);
    }
  }
}