/*
 * Copyright 2003-2012 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ipp.concatenation;

import com.intellij.java.impl.ipp.base.PsiElementPredicate;
import com.intellij.java.language.psi.*;
import consulo.language.ast.IElementType;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiWhiteSpace;

class StringConcatPredicate implements PsiElementPredicate {

  public boolean satisfiedBy(PsiElement element) {
    if (element instanceof PsiWhiteSpace) {
      element = element.getPrevSibling();
    }
    if (!(element instanceof PsiJavaToken)) {
      return false;
    }
    final PsiJavaToken token = (PsiJavaToken)element;
    final IElementType tokenType = token.getTokenType();
    if (!tokenType.equals(JavaTokenType.PLUS)) {
      return false;
    }
    final PsiElement parent = element.getParent();
    if (!(parent instanceof PsiPolyadicExpression)) {
      return false;
    }
    final PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)parent;
    final PsiType type = polyadicExpression.getType();
    if (type == null || !type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
      return false;
    }
    final PsiExpression[] operands = polyadicExpression.getOperands();
    PsiExpression previous = null;
    boolean stringTypeSeen = false;
    for (int i = 0, length = operands.length; i < length; i++) {
      final PsiExpression operand = operands[i];
      final PsiType operandType = operand.getType();
      final PsiJavaToken currentToken = polyadicExpression.getTokenBeforeOperand(operand);
      if (token == currentToken) {
        if (!(previous instanceof PsiLiteralExpression) || !(operand instanceof PsiLiteralExpression)) {
          return false;
        }
        return stringTypeSeen || (i == 1 && operandType != null && operandType.equalsToText(CommonClassNames.JAVA_LANG_STRING));
      }
      previous = operand;
      if (!stringTypeSeen) {
        stringTypeSeen = operandType != null && operandType.equalsToText(CommonClassNames.JAVA_LANG_STRING);
      }
    }
    return false;
  }
}