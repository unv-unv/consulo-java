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
package com.intellij.java.impl.ig.threading;

import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiUtil;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.deadCodeNotWorking.impl.SingleCheckboxOptionsPanel;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;

@ExtensionImpl
public class WhileLoopSpinsOnFieldInspection extends BaseInspection {

  @SuppressWarnings({"PublicField"})
  public boolean ignoreNonEmtpyLoops = false;

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.whileLoopSpinsOnFieldDisplayName().get();
  }

  @Override
  @Nonnull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.whileLoopSpinsOnFieldProblemDescriptor().get();
  }

  @Override
  @Nullable
  public JComponent createOptionsPanel() {
    LocalizeValue message = InspectionGadgetsLocalize.whileLoopSpinsOnFieldIgnoreNonEmptyLoopsOption();
    return new SingleCheckboxOptionsPanel(message.get(), this, "ignoreNonEmtpyLoops");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new WhileLoopSpinsOnFieldVisitor();
  }

  private class WhileLoopSpinsOnFieldVisitor extends BaseInspectionVisitor {

    @Override
    public void visitWhileStatement(@Nonnull PsiWhileStatement statement) {
      super.visitWhileStatement(statement);
      final PsiStatement body = statement.getBody();
      if (ignoreNonEmtpyLoops && !statementIsEmpty(body)) {
        return;
      }
      final PsiExpression condition = statement.getCondition();
      final PsiField field = getFieldIfSimpleFieldComparison(condition);
      if (field == null) {
        return;
      }
      if (body != null && VariableAccessUtils.variableIsAssigned(field, body)) {
        return;
      }
      registerStatementError(statement);
    }

    @Nullable
    private PsiField getFieldIfSimpleFieldComparison(PsiExpression condition) {
      condition = PsiUtil.deparenthesizeExpression(condition);
      if (condition == null) {
        return null;
      }
      final PsiField field = getFieldIfSimpleFieldAccess(condition);
      if (field != null) {
        return field;
      }
      if (condition instanceof PsiPrefixExpression) {
        final PsiPrefixExpression prefixExpression = (PsiPrefixExpression)condition;
        final PsiExpression operand = prefixExpression.getOperand();
        return getFieldIfSimpleFieldComparison(operand);
      }
      if (condition instanceof PsiPostfixExpression) {
        final PsiPostfixExpression postfixExpression = (PsiPostfixExpression)condition;
        final PsiExpression operand = postfixExpression.getOperand();
        return getFieldIfSimpleFieldComparison(operand);
      }
      if (condition instanceof PsiBinaryExpression) {
        final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)condition;
        final PsiExpression lOperand = binaryExpression.getLOperand();
        final PsiExpression rOperand = binaryExpression.getROperand();
        if (ExpressionUtils.isLiteral(rOperand)) {
          return getFieldIfSimpleFieldComparison(lOperand);
        }
        else if (ExpressionUtils.isLiteral(lOperand)) {
          return getFieldIfSimpleFieldComparison(rOperand);
        }
        else {
          return null;
        }
      }
      return null;
    }

    @Nullable
    private PsiField getFieldIfSimpleFieldAccess(PsiExpression expression) {
      expression = PsiUtil.deparenthesizeExpression(expression);
      if (expression == null) {
        return null;
      }
      if (!(expression instanceof PsiReferenceExpression)) {
        return null;
      }
      final PsiReferenceExpression reference = (PsiReferenceExpression)expression;
      final PsiExpression qualifierExpression = reference.getQualifierExpression();
      if (qualifierExpression != null) {
        return null;
      }
      final PsiElement referent = reference.resolve();
      if (!(referent instanceof PsiField)) {
        return null;
      }
      final PsiField field = (PsiField)referent;
      if (field.hasModifierProperty(PsiModifier.VOLATILE)) {
        return null;
      }
      else {
        return field;
      }
    }

    private boolean statementIsEmpty(PsiStatement statement) {
      if (statement == null) {
        return false;
      }
      if (statement instanceof PsiEmptyStatement) {
        return true;
      }
      if (statement instanceof PsiBlockStatement) {
        final PsiBlockStatement blockStatement = (PsiBlockStatement)statement;
        final PsiCodeBlock codeBlock = blockStatement.getCodeBlock();
        final PsiStatement[] codeBlockStatements = codeBlock.getStatements();
        for (PsiStatement codeBlockStatement : codeBlockStatements) {
          if (!statementIsEmpty(codeBlockStatement)) {
            return false;
          }
        }
        return true;
      }
      return false;
    }
  }
}