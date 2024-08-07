/*
 * Copyright 2003-2013 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.controlflow;

import com.intellij.java.language.psi.*;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.EquivalenceChecker;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.deadCodeNotWorking.impl.SingleCheckboxOptionsPanel;
import consulo.language.ast.IElementType;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@ExtensionImpl
public class DuplicateConditionInspection extends BaseInspection {

  /**
   * @noinspection PublicField
   */
  public boolean ignoreMethodCalls = false;

  // This is a dirty fix of 'squared' algorithm performance issue.
  private static final int LIMIT_DEPTH = 20;

  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.duplicateConditionDisplayName().get();
  }

  @Nonnull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.duplicateConditionProblemDescriptor().get();
  }

  @Nullable
  public JComponent createOptionsPanel() {
    LocalizeValue message = InspectionGadgetsLocalize.duplicateConditionIgnoreMethodCallsOption();
    return new SingleCheckboxOptionsPanel(message.get(), this, "ignoreMethodCalls");
  }

  public BaseInspectionVisitor buildVisitor() {
    return new DuplicateConditionVisitor();
  }

  private class DuplicateConditionVisitor extends BaseInspectionVisitor {

    @Override
    public void visitIfStatement(@Nonnull PsiIfStatement statement) {
      super.visitIfStatement(statement);
      final PsiElement parent = statement.getParent();
      if (parent instanceof PsiIfStatement) {
        final PsiIfStatement parentStatement = (PsiIfStatement)parent;
        final PsiStatement elseBranch = parentStatement.getElseBranch();
        if (statement.equals(elseBranch)) {
          return;
        }
      }
      final Set<PsiExpression> conditions = new HashSet<PsiExpression>();
      collectConditionsForIfStatement(statement, conditions, 0);
      final int numConditions = conditions.size();
      if (numConditions < 2) {
        return;
      }
      final PsiExpression[] conditionArray = conditions.toArray(new PsiExpression[numConditions]);
      final boolean[] matched = new boolean[conditionArray.length];
      Arrays.fill(matched, false);
      for (int i = 0; i < conditionArray.length; i++) {
        if (matched[i]) {
          continue;
        }
        final PsiExpression condition = conditionArray[i];
        for (int j = i + 1; j < conditionArray.length; j++) {
          if (matched[j]) {
            continue;
          }
          final PsiExpression testCondition = conditionArray[j];
          final boolean areEquivalent = EquivalenceChecker.getCanonicalPsiEquivalence().getCanonicalPsiEquivalence().expressionsAreEquivalent(condition, testCondition);
          if (areEquivalent) {
            if (!ignoreMethodCalls || !containsMethodCallExpression(testCondition)) {
              registerError(testCondition);
              if (!matched[i]) {
                registerError(condition);
              }
            }
            matched[i] = true;
            matched[j] = true;
          }
        }
      }
    }

    private void collectConditionsForIfStatement(PsiIfStatement statement, Set<PsiExpression> conditions, int depth) {
      if (depth > LIMIT_DEPTH) {
        return;
      }
      final PsiExpression condition = statement.getCondition();
      collectConditionsForExpression(condition, conditions);
      final PsiStatement branch = statement.getElseBranch();
      if (branch instanceof PsiIfStatement) {
        collectConditionsForIfStatement((PsiIfStatement)branch, conditions, depth + 1);
      }
    }

    private void collectConditionsForExpression(PsiExpression condition, Set<PsiExpression> conditions) {
      if (condition == null) {
        return;
      }
      if (condition instanceof PsiParenthesizedExpression) {
        final PsiParenthesizedExpression parenthesizedExpression = (PsiParenthesizedExpression)condition;
        final PsiExpression contents = parenthesizedExpression.getExpression();
        collectConditionsForExpression(contents, conditions);
        return;
      }
      if (condition instanceof PsiPolyadicExpression) {
        final PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)condition;
        final IElementType tokenType = polyadicExpression.getOperationTokenType();
        if (JavaTokenType.OROR.equals(tokenType)) {
          final PsiExpression[] operands = polyadicExpression.getOperands();
          for (PsiExpression operand : operands) {
            collectConditionsForExpression(operand, conditions);
          }
          return;
        }
      }
      conditions.add(condition);
    }

    private boolean containsMethodCallExpression(PsiElement element) {
      if (element instanceof PsiMethodCallExpression) {
        return true;
      }
      final PsiElement[] children = element.getChildren();
      for (PsiElement child : children) {
        if (containsMethodCallExpression(child)) {
          return true;
        }
      }
      return false;
    }
  }
}
