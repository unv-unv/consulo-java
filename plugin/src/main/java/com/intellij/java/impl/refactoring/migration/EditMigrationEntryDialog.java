/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.migration;

import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.psi.JavaCodeFragment;
import com.intellij.java.language.psi.JavaCodeFragmentFactory;
import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiNameHelper;
import consulo.document.Document;
import consulo.document.event.DocumentEvent;
import consulo.document.event.DocumentListener;
import consulo.language.Language;
import consulo.language.editor.refactoring.RefactoringBundle;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.ui.awt.EditorTextField;
import consulo.language.editor.ui.awt.LanguageTextField;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiManager;
import consulo.language.psi.PsiPackage;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBUI;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.awt.*;

public class EditMigrationEntryDialog extends DialogWrapper {
  private JRadioButton myRbPackage;
  private JRadioButton myRbClass;
  private EditorTextField myOldNameField;
  private EditorTextField myNewNameField;
  private final Project myProject;

  public EditMigrationEntryDialog(Project project) {
    super(project, true);
    myProject = project;
    setTitle(RefactoringLocalize.editMigrationEntryTitle());
    setHorizontalStretch(1.2f);
    init();
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myOldNameField;
  }

  @Override
  protected JComponent createCenterPanel() {
    return null;
  }

  @Override
  protected JComponent createNorthPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbConstraints = new GridBagConstraints();
    gbConstraints.insets = JBUI.insets(4);
    gbConstraints.weighty = 0;

    gbConstraints.gridwidth = GridBagConstraints.RELATIVE;
    gbConstraints.fill = GridBagConstraints.BOTH;
    gbConstraints.weightx = 0;
    myRbPackage = new JRadioButton(RefactoringLocalize.migrationEntryPackage().get());
    panel.add(myRbPackage, gbConstraints);

    gbConstraints.gridwidth = GridBagConstraints.RELATIVE;
    gbConstraints.fill = GridBagConstraints.BOTH;
    gbConstraints.weightx = 0;
    myRbClass = new JRadioButton(RefactoringLocalize.migrationEntryClass().get());
    panel.add(myRbClass, gbConstraints);

    gbConstraints.gridwidth = GridBagConstraints.REMAINDER;
    gbConstraints.fill = GridBagConstraints.BOTH;
    gbConstraints.weightx = 1;
    panel.add(new JPanel(), gbConstraints);

    ButtonGroup buttonGroup = new ButtonGroup();
    buttonGroup.add(myRbPackage);
    buttonGroup.add(myRbClass);

    gbConstraints.weightx = 0;
    gbConstraints.gridwidth = GridBagConstraints.RELATIVE;
    gbConstraints.fill = GridBagConstraints.NONE;
    JLabel oldNamePrompt = new JLabel(RefactoringLocalize.migrationEntryOldName().get());
    panel.add(oldNamePrompt, gbConstraints);

    gbConstraints.gridwidth = GridBagConstraints.REMAINDER;
    gbConstraints.fill = GridBagConstraints.HORIZONTAL;
    gbConstraints.weightx = 1;
    final LanguageTextField.DocumentCreator documentCreator = new LanguageTextField.DocumentCreator() {
      @Override
      public Document createDocument(String value, @Nullable Language language, Project project) {
        PsiPackage defaultPackage = JavaPsiFacade.getInstance(project).findPackage("");
        final JavaCodeFragment fragment = JavaCodeFragmentFactory.getInstance(project).createReferenceCodeFragment("", defaultPackage, true, true);
        return PsiDocumentManager.getInstance(project).getDocument(fragment);
      }
    };
    myOldNameField = new LanguageTextField(JavaLanguage.INSTANCE, myProject, "", documentCreator);
    panel.add(myOldNameField, gbConstraints);

    gbConstraints.weightx = 0;
    gbConstraints.gridwidth = GridBagConstraints.RELATIVE;
    gbConstraints.fill = GridBagConstraints.NONE;
    JLabel newNamePrompt = new JLabel(RefactoringLocalize.migrationEntryNewName().get());
    panel.add(newNamePrompt, gbConstraints);

    gbConstraints.gridwidth = GridBagConstraints.REMAINDER;
    gbConstraints.fill = GridBagConstraints.HORIZONTAL;
    gbConstraints.weightx = 1;
    myNewNameField = new LanguageTextField(JavaLanguage.INSTANCE, myProject, "", documentCreator);
    panel.setPreferredSize(new Dimension(300, panel.getPreferredSize().height));
    panel.add(myNewNameField, gbConstraints);

    final DocumentListener documentAdapter = new DocumentListener() {
      @Override
      public void documentChanged(DocumentEvent e) {
        validateOKButton();
      }
    };
    myOldNameField.getDocument().addDocumentListener(documentAdapter);
    myNewNameField.getDocument().addDocumentListener(documentAdapter);
    return panel;
  }

  private void validateOKButton() {
    boolean isEnabled = true;
    String text = myOldNameField.getText();
    text = text.trim();
    PsiManager manager = PsiManager.getInstance(myProject);
    if (!PsiNameHelper.getInstance(manager.getProject()).isQualifiedName(text)) {
      isEnabled = false;
    }
    text = myNewNameField.getText();
    text = text.trim();
    if (!PsiNameHelper.getInstance(manager.getProject()).isQualifiedName(text)) {
      isEnabled = false;
    }
    setOKActionEnabled(isEnabled);
  }

  public void setEntry(MigrationMapEntry entry) {
    myOldNameField.setText(entry.getOldName());
    myNewNameField.setText(entry.getNewName());
    myRbPackage.setSelected(entry.getType() == MigrationMapEntry.PACKAGE);
    myRbClass.setSelected(entry.getType() == MigrationMapEntry.CLASS);
    validateOKButton();
  }

  public void updateEntry(MigrationMapEntry entry) {
    entry.setOldName(myOldNameField.getText().trim());
    entry.setNewName(myNewNameField.getText().trim());
    if (myRbPackage.isSelected()) {
      entry.setType(MigrationMapEntry.PACKAGE);
      entry.setRecursive(true);
    } else {
      entry.setType(MigrationMapEntry.CLASS);
    }
  }
}
