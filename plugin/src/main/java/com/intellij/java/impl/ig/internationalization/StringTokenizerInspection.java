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
package com.intellij.java.impl.ig.internationalization;

import com.intellij.java.language.psi.*;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.TypeUtils;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import jakarta.annotation.Nonnull;

@ExtensionImpl
public class StringTokenizerInspection extends BaseInspection {

  @Nonnull
  public String getID() {
    return "UseOfStringTokenizer";
  }

  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.useStringtokenizerDisplayName().get();
  }

  @Nonnull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.useStringtokenizerProblemDescriptor().get();
  }

  public BaseInspectionVisitor buildVisitor() {
    return new StringTokenizerVisitor();
  }

  private static class StringTokenizerVisitor extends BaseInspectionVisitor {

    @Override
    public void visitVariable(@Nonnull PsiVariable variable) {
      super.visitVariable(variable);
      final PsiType type = variable.getType();
      final PsiType deepComponentType = type.getDeepComponentType();
      if (!TypeUtils.typeEquals("java.util.StringTokenizer",
                                deepComponentType)) {
        return;
      }
      final PsiTypeElement typeElement = variable.getTypeElement();
      if (typeElement == null) {
        return;
      }
      final PsiExpression initializer = variable.getInitializer();
      if (isTokenizingNonNlsAnnotatedElement(initializer)) {
        return;
      }
      registerError(typeElement);
    }

    private static boolean isTokenizingNonNlsAnnotatedElement(
      PsiExpression initializer) {
      if (!(initializer instanceof PsiNewExpression)) {
        return false;
      }
      final PsiNewExpression newExpression =
        (PsiNewExpression)initializer;
      final PsiExpressionList argumentList =
        newExpression.getArgumentList();
      if (argumentList == null) {
        return false;
      }
      final PsiExpression[] expressions =
        argumentList.getExpressions();
      if (expressions.length <= 0) {
        return false;
      }
      return NonNlsUtils.isNonNlsAnnotated(expressions[0]);
    }
  }
}