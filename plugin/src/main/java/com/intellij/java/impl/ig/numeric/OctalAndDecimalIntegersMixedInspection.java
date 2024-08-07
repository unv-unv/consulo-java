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
package com.intellij.java.impl.ig.numeric;

import com.intellij.java.language.psi.PsiArrayInitializerExpression;
import com.intellij.java.language.psi.PsiExpression;
import com.intellij.java.language.psi.PsiLiteralExpression;
import com.intellij.java.language.psi.PsiType;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.NonNls;

@ExtensionImpl
public class OctalAndDecimalIntegersMixedInspection
  extends BaseInspection {

  @Override
  @Nonnull
  public String getID() {
    return "OctalAndDecimalIntegersInSameArray";
  }

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.octalAndDecimalIntegersInSameArrayDisplayName().get();
  }

  @Override
  @Nonnull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.octalAndDecimalIntegersInSameArrayProblemDescriptor().get();
  }

  @Nonnull
  @Override
  protected InspectionGadgetsFix[] buildFixes(Object... infos) {
    return new InspectionGadgetsFix[]{
      new ConvertOctalLiteralToDecimalFix(),
      new RemoveLeadingZeroFix()
    };
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new OctalAndDecimalIntegersMixedVisitor();
  }

  private static class OctalAndDecimalIntegersMixedVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitArrayInitializerExpression(
      PsiArrayInitializerExpression expression) {
      super.visitArrayInitializerExpression(expression);
      final PsiExpression[] initializers = expression.getInitializers();
      boolean hasDecimalLiteral = false;
      boolean hasOctalLiteral = false;
      for (final PsiExpression initializer : initializers) {
        if (initializer instanceof PsiLiteralExpression) {
          final PsiLiteralExpression literal =
            (PsiLiteralExpression)initializer;
          if (isDecimalLiteral(literal)) {
            hasDecimalLiteral = true;
          }
          if (isOctalLiteral(literal)) {
            hasOctalLiteral = true;
          }
        }
      }
      if (hasOctalLiteral && hasDecimalLiteral) {
        registerError(expression);
      }
    }

    private static boolean isDecimalLiteral(PsiLiteralExpression literal) {
      final PsiType type = literal.getType();
      if (!PsiType.INT.equals(type) &&
          !PsiType.LONG.equals(type)) {
        return false;
      }
      final String text = literal.getText();
      if ("0".equals(text)) {
        return false;
      }
      return text.charAt(0) != '0';
    }

    private static boolean isOctalLiteral(PsiLiteralExpression literal) {
      final PsiType type = literal.getType();
      if (!PsiType.INT.equals(type) && !PsiType.LONG.equals(type)) {
        return false;
      }
      @NonNls final String text = literal.getText();
      if ("0".equals(text) || "0L".equals(text)) {
        return false;
      }
      return text.charAt(0) == '0' && !text.startsWith("0x") &&
             !text.startsWith("0X");
    }
  }
}