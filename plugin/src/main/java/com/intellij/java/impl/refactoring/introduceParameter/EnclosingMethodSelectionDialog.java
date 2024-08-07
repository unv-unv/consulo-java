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

/*
 * Created by IntelliJ IDEA.
 * User: dsl
 * Date: 06.06.2002
 * Time: 11:30:13
 * To change template for new class use 
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.java.impl.refactoring.introduceParameter;

import com.intellij.java.impl.refactoring.ui.MethodCellRenderer;
import com.intellij.java.language.psi.PsiMethod;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBList;
import consulo.ui.ex.awt.ScrollPaneFactory;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class EnclosingMethodSelectionDialog extends DialogWrapper {
  private final List<PsiMethod> myEnclosingMethods;

  private JList myEnclosingMethodsList = null;
  private final JCheckBox myCbReplaceInstanceOf = new JCheckBox(RefactoringLocalize.useInterfaceSuperclassInInstanceof().get());
  private static final String REFACTORING_NAME = RefactoringLocalize.introduceParameterTitle().get();

  EnclosingMethodSelectionDialog(Project project, List<PsiMethod> enclosingMethods) {
    super(project, true);

    myEnclosingMethods = enclosingMethods;

    setTitle(REFACTORING_NAME);
    init();
  }

  public PsiMethod getSelectedMethod() {
    if(myEnclosingMethodsList != null) {
      return (PsiMethod) myEnclosingMethodsList.getSelectedValue();
    }
    else {
      return null;
    }
  }

  @Nonnull
  protected Action[] createActions() {
    return new Action[]{getOKAction(), getCancelAction()/*, getHelpAction()*/};
  }

  public JComponent getPreferredFocusedComponent() {
    return myEnclosingMethodsList;
  }

  protected JComponent createNorthPanel() {
    JPanel panel = new JPanel();

    panel.setLayout(new GridBagLayout());
    GridBagConstraints gbConstraints = new GridBagConstraints();

    gbConstraints.insets = new Insets(4, 8, 4, 8);
    gbConstraints.weighty = 0;
    gbConstraints.weightx = 1;
    gbConstraints.gridy = 0;
    gbConstraints.gridwidth = GridBagConstraints.REMAINDER;
    gbConstraints.gridheight = 1;
    gbConstraints.fill = GridBagConstraints.BOTH;
    gbConstraints.anchor = GridBagConstraints.WEST;
    panel.add(new JLabel(RefactoringLocalize.introduceParameterToMethod().get()), gbConstraints);

    gbConstraints.weighty = 1;
    myEnclosingMethodsList = new JBList(myEnclosingMethods.toArray());
    myEnclosingMethodsList.setCellRenderer(new MethodCellRenderer());
    myEnclosingMethodsList.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    int indexToSelect = 0;
    myEnclosingMethodsList.setSelectedIndex(indexToSelect);
    gbConstraints.gridy++;
    panel.add(ScrollPaneFactory.createScrollPane(myEnclosingMethodsList), gbConstraints);

    return panel;
  }

  protected String getDimensionServiceKey() {
    return "#com.intellij.refactoring.introduceParameter.EnclosingMethodSelectonDialog";
  }

  protected void doOKAction() {
    if (!isOKActionEnabled())
      return;

    super.doOKAction();
  }

  protected JComponent createCenterPanel() {
    return null;
  }

}
