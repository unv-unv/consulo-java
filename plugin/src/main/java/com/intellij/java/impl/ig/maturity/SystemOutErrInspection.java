/*
 * Copyright 2003-2007 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.maturity;

import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiField;
import com.intellij.java.language.psi.PsiReferenceExpression;
import com.siyeh.HardcodedMethodConstants;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.psi.PsiElement;
import jakarta.annotation.Nonnull;

@ExtensionImpl
public class SystemOutErrInspection extends BaseInspection {

  @Nonnull
  public String getID() {
    return "UseOfSystemOutOrSystemErr";
  }

  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.useSystemOutErrDisplayName().get();
  }

  @Nonnull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.useSystemOutErrProblemDescriptor().get();
  }

  public BaseInspectionVisitor buildVisitor() {
    return new SystemOutErrVisitor();
  }

  private static class SystemOutErrVisitor extends BaseInspectionVisitor {

    @Override
    public void visitReferenceExpression(
      @Nonnull PsiReferenceExpression expression) {
      super.visitReferenceExpression(expression);
      final String name = expression.getReferenceName();
      if (!HardcodedMethodConstants.OUT.equals(name) &&
          !HardcodedMethodConstants.ERR.equals(name)) {
        return;
      }
      final PsiElement referent = expression.resolve();
      if (!(referent instanceof PsiField)) {
        return;
      }
      final PsiField field = (PsiField)referent;
      final PsiClass containingClass = field.getContainingClass();
      if (containingClass == null) {
        return;
      }
      final String className = containingClass.getQualifiedName();
      if (!"java.lang.System".equals(className)) {
        return;
      }
      registerError(expression);
    }
  }
}