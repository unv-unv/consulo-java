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

import com.intellij.java.impl.ig.psiutils.SwitchUtils;
import com.intellij.java.language.psi.*;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.deadCodeNotWorking.impl.SingleIntegerFieldOptionsPanel;
import consulo.localize.LocalizeValue;
import jakarta.annotation.Nonnull;

import javax.swing.*;

public abstract class SwitchStatementDensityInspection extends BaseInspection {

  private static final int DEFAULT_DENSITY_LIMIT = 20;

  @SuppressWarnings("PublicField")
  public int m_limit = DEFAULT_DENSITY_LIMIT;

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.switchStatementDensityDisplayName().get();
  }

  @Override
  public JComponent createOptionsPanel() {
    LocalizeValue message = InspectionGadgetsLocalize.switchStatementDensityMinOption();
    return new SingleIntegerFieldOptionsPanel(message.get(), this, "m_limit");
  }

  @Override
  @Nonnull
  protected String buildErrorString(Object... infos) {
    final Integer intDensity = (Integer)infos[0];
    return InspectionGadgetsLocalize.switchStatementDensityProblemDescriptor(intDensity).get();
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SwitchStatementDensityVisitor();
  }

  private class SwitchStatementDensityVisitor extends BaseInspectionVisitor {
    @Override
    public void visitSwitchStatement(@Nonnull PsiSwitchStatement statement) {
      final PsiCodeBlock body = statement.getBody();
      if (body == null) {
        return;
      }
      final int branchCount = SwitchUtils.calculateBranchCount(statement);
      if (branchCount == 0) {
        return;
      }
      final double density = calculateDensity(body, branchCount);
      final int intDensity = (int)(density * 100.0);
      if (intDensity > m_limit) {
        return;
      }
      registerStatementError(statement, Integer.valueOf(intDensity));
    }

    private double calculateDensity(@Nonnull PsiCodeBlock body, int branchCount) {
      final StatementCountVisitor visitor = new StatementCountVisitor();
      body.accept(visitor);
      return (double)branchCount / (double)visitor.getStatementCount();
    }
  }

  private static class StatementCountVisitor extends JavaRecursiveElementVisitor {

    private int statementCount = 0;

    @Override
    public void visitStatement(@Nonnull PsiStatement statement) {
      super.visitStatement(statement);
      if (statement instanceof PsiSwitchLabelStatement || statement instanceof PsiBreakStatement) {
        return;
      }
      statementCount++;
    }

    public int getStatementCount() {
      return statementCount;
    }
  }
}