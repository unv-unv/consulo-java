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
package com.intellij.java.impl.refactoring.inline;

import com.intellij.java.impl.refactoring.HelpID;
import com.intellij.java.language.psi.PsiCallExpression;
import com.intellij.java.language.psi.PsiExpression;
import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.language.psi.PsiParameter;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.ui.RefactoringDialog;

import javax.swing.*;
import java.awt.*;

/**
 * @author yole
 */
public class InlineParameterDialog extends RefactoringDialog {
  private JCheckBox myCreateLocalCheckbox;
  private final PsiCallExpression myMethodCall;
  private final PsiMethod myMethod;
  private final PsiParameter myParameter;
  private final PsiExpression myInitializer;

  public InlineParameterDialog(PsiCallExpression methodCall, PsiMethod method, PsiParameter psiParameter, PsiExpression initializer,
                               boolean createLocal) {
    super(method.getProject(), true);
    myMethodCall = methodCall;
    myMethod = method;
    myParameter = psiParameter;
    myInitializer = initializer;
    init();
    myCreateLocalCheckbox.setSelected(createLocal);
    setTitle(InlineParameterHandler.REFACTORING_NAME);
  }

  @Override
  protected JComponent createNorthPanel() {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.add(
      new JLabel(
        RefactoringLocalize.inlineParameterConfirmation(myParameter.getName(), myInitializer.getText()).get(),
        UIManager.getIcon("OptionPane.questionIcon"),
        2
      ),
      BorderLayout.NORTH
    );
    return panel;
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    myCreateLocalCheckbox = new JCheckBox(RefactoringLocalize.inlineParameterReplaceWithLocalCheckbox().get());
    panel.add(myCreateLocalCheckbox, BorderLayout.SOUTH);
    return panel;
  }

  @Override
  protected String getHelpId() {
    return HelpID.INLINE_VARIABLE;
  }

  @Override
  protected void doAction() {
    invokeRefactoring(new InlineParameterExpressionProcessor(myMethodCall, myMethod, myParameter, myInitializer, myCreateLocalCheckbox.isSelected()));
  }
}
