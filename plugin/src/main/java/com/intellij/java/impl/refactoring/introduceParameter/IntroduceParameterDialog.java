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
 * Date: 06.05.2002
 * Time: 16:54:19
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.java.impl.refactoring.introduceParameter;

import com.intellij.java.impl.refactoring.HelpID;
import com.intellij.java.impl.refactoring.JavaRefactoringSettings;
import com.intellij.java.impl.refactoring.ui.NameSuggestionsGenerator;
import com.intellij.java.impl.refactoring.ui.NameSuggestionsManager;
import com.intellij.java.impl.refactoring.ui.TypeSelector;
import com.intellij.java.impl.refactoring.ui.TypeSelectorManager;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.application.HelpManager;
import consulo.configurable.ConfigurationException;
import consulo.language.codeStyle.CodeStyleSettingsManager;
import consulo.language.editor.refactoring.RefactoringBundle;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.ui.NameSuggestionsField;
import consulo.language.editor.refactoring.ui.RefactoringDialog;
import consulo.project.Project;
import consulo.ui.ex.awt.NonFocusableCheckBox;
import consulo.usage.UsageInfo;
import consulo.util.collection.primitive.ints.IntList;

import jakarta.annotation.Nonnull;
import javax.swing.*;
import java.awt.*;
import java.util.List;

public class IntroduceParameterDialog extends RefactoringDialog {
  private TypeSelector myTypeSelector;
  private NameSuggestionsManager myNameSuggestionsManager;

  private final Project myProject;
  private final List<UsageInfo> myClassMembersList;
  private final int myOccurenceNumber;
  private final PsiMethod myMethodToSearchFor;
  private final PsiMethod myMethodToReplaceIn;
  private final boolean myMustBeFinal;
  private final PsiExpression myExpression;
  private final PsiLocalVariable myLocalVar;
  protected JCheckBox myCbDeclareFinal = null;

  //  private JComponent myParameterNameField = null;
  private NameSuggestionsField myParameterNameField;


  private final NameSuggestionsGenerator myNameSuggestionsGenerator;
  private final TypeSelectorManager myTypeSelectorManager;
  private static final String REFACTORING_NAME = RefactoringBundle.message("introduce.parameter.title");
  private NameSuggestionsField.DataChanged myParameterNameChangedListener;

  private final IntroduceParameterSettingsPanel myPanel;
  private boolean myHasWriteAccess = false;

  IntroduceParameterDialog(@Nonnull Project project,
                           @Nonnull List<UsageInfo> classMembersList,
                           PsiExpression[] occurences,
                           PsiLocalVariable onLocalVariable,
                           PsiExpression onExpression,
                           @Nonnull NameSuggestionsGenerator generator,
                           @Nonnull TypeSelectorManager typeSelectorManager,
                           @Nonnull PsiMethod methodToSearchFor,
                           @Nonnull PsiMethod methodToReplaceIn,
                           @Nonnull IntList parametersToRemove,
                           final boolean mustBeFinal) {
    super(project, true);
    myPanel = new IntroduceParameterSettingsPanel(onLocalVariable, onExpression, methodToReplaceIn, parametersToRemove);
    myProject = project;
    myClassMembersList = classMembersList;
    myOccurenceNumber = occurences.length;
    for (PsiExpression occurence : occurences) {
      if (PsiUtil.isAccessedForWriting(occurence)) {
        myHasWriteAccess = true;
        break;
      }
    }
    myExpression = onExpression;
    myLocalVar = onLocalVariable;
    myMethodToReplaceIn = methodToReplaceIn;
    myMustBeFinal = mustBeFinal;
    myMethodToSearchFor = methodToSearchFor;
    myNameSuggestionsGenerator = generator;
    myTypeSelectorManager = typeSelectorManager;
    setTitle(REFACTORING_NAME);
    init();
  }

  protected void dispose() {
    myParameterNameField.removeDataChangedListener(myParameterNameChangedListener);
    super.dispose();
  }

  private boolean isDeclareFinal() {
    return myCbDeclareFinal != null && myCbDeclareFinal.isSelected();
  }



  private String getParameterName() {
    return  myParameterNameField.getEnteredName().trim();
  }

  public JComponent getPreferredFocusedComponent() {
    return myParameterNameField.getFocusableComponent();
  }

  private PsiType getSelectedType() {
    return myTypeSelector.getSelectedType();
  }

  protected void doHelpAction() {
    HelpManager.getInstance().invokeHelp(HelpID.INTRODUCE_PARAMETER);
  }

  protected JComponent createNorthPanel() {
    GridBagConstraints gbConstraints = new GridBagConstraints();

    JPanel panel = new JPanel(new GridBagLayout());

    gbConstraints.anchor = GridBagConstraints.WEST;
    gbConstraints.fill = GridBagConstraints.NONE;
    gbConstraints.gridx = 0;

    gbConstraints.insets = new Insets(4, 4, 4, 0);
    gbConstraints.gridwidth = 1;
    gbConstraints.weightx = 0;
    gbConstraints.weighty = 0;
    gbConstraints.gridy = 0;
    JLabel type = new JLabel(RefactoringLocalize.parameterOfType().get());
    panel.add(type, gbConstraints);

    gbConstraints.insets = new Insets(4, 4, 4, 8);
    gbConstraints.gridx++;
    gbConstraints.weightx = 1;
    gbConstraints.fill = GridBagConstraints.BOTH;
    myTypeSelector = myTypeSelectorManager.getTypeSelector();
    panel.add(myTypeSelector.getComponent(), gbConstraints);


    gbConstraints.insets = new Insets(4, 4, 4, 8);
    gbConstraints.gridwidth = 1;
    gbConstraints.weightx = 0;
    gbConstraints.gridx = 0;
    gbConstraints.gridy = 1;
    gbConstraints.fill = GridBagConstraints.NONE;

    myParameterNameField = new NameSuggestionsField(myProject);
    final JLabel nameLabel = new JLabel(RefactoringLocalize.namePrompt().get());
    nameLabel.setLabelFor(myParameterNameField.getComponent());
    panel.add(nameLabel, gbConstraints);

/*
    if (myNameSuggestions.length > 1) {
      myParameterNameField = createComboBoxForName();
    }
    else {
      myParameterNameField = createTextFieldForName();
    }
*/
    gbConstraints.gridx++;
    gbConstraints.insets = new Insets(4, 4, 4, 8);
    gbConstraints.weightx = 1;
    gbConstraints.fill = GridBagConstraints.BOTH;
    panel.add(myParameterNameField.getComponent(), gbConstraints);
    myParameterNameChangedListener = new NameSuggestionsField.DataChanged() {
      public void dataChanged() {
        validateButtons();
      }
    };
    myParameterNameField.addDataChangedListener(myParameterNameChangedListener);

    myNameSuggestionsManager =
            new NameSuggestionsManager(myTypeSelector, myParameterNameField, myNameSuggestionsGenerator);
    myNameSuggestionsManager.setLabelsFor(type, nameLabel);

    gbConstraints.gridx = 0;
    gbConstraints.insets = new Insets(4, 0, 4, 8);
    gbConstraints.gridwidth = 2;
    if (myOccurenceNumber > 1 && !myPanel.myIsInvokedOnDeclaration) {
      gbConstraints.gridy++;
      myPanel.createOccurrencesCb(gbConstraints, panel, myOccurenceNumber);
    }
    if(myPanel.myCbReplaceAllOccurences != null) {
      gbConstraints.insets = new Insets(0, 16, 4, 8);
    }
    JavaRefactoringSettings settings = JavaRefactoringSettings.getInstance();
    myPanel.createLocalVariablePanel(gbConstraints, panel, settings);

    myPanel.createRemoveParamsPanel(gbConstraints, panel);
    gbConstraints.insets =  new Insets(4, 0, 4, 8);

    gbConstraints.gridy++;
    myCbDeclareFinal = new NonFocusableCheckBox(RefactoringLocalize.declareFinal().get());

    final Boolean settingsFinals = settings.INTRODUCE_PARAMETER_CREATE_FINALS;
    myCbDeclareFinal.setSelected(settingsFinals == null ?
                                 CodeStyleSettingsManager.getSettings(myProject).GENERATE_FINAL_PARAMETERS :
                                 settingsFinals.booleanValue());
    panel.add(myCbDeclareFinal, gbConstraints);
    if (myMustBeFinal) {
      myCbDeclareFinal.setSelected(true);
      myCbDeclareFinal.setEnabled(false);
    } else if (myHasWriteAccess && myPanel.isReplaceAllOccurences()) {
      myCbDeclareFinal.setSelected(false);
      myCbDeclareFinal.setEnabled(false);
    }

    gbConstraints.gridy++;
    myPanel.createDelegateCb(gbConstraints, panel);

    return panel;
  }


  protected JComponent createCenterPanel() {
    if(Util.anyFieldsWithGettersPresent(myClassMembersList)) {
      return myPanel.createReplaceFieldsWithGettersPanel();
    }
    else
      return null;
  }

  protected void doAction() {
    final JavaRefactoringSettings settings = JavaRefactoringSettings.getInstance();
    settings.INTRODUCE_PARAMETER_REPLACE_FIELDS_WITH_GETTERS =
            myPanel.getReplaceFieldsWithGetters();
    if (myCbDeclareFinal != null && myCbDeclareFinal.isEnabled()) {
      settings.INTRODUCE_PARAMETER_CREATE_FINALS = Boolean.valueOf(myCbDeclareFinal.isSelected());
    }

    myPanel.saveSettings(settings);

    myNameSuggestionsManager.nameSelected();

    boolean isDeleteLocalVariable = false;

    PsiExpression parameterInitializer = myExpression;
    if (myLocalVar != null) {
      if (myPanel.isUseInitializer()) {
      parameterInitializer = myLocalVar.getInitializer();      }
      isDeleteLocalVariable = myPanel.isDeleteLocalVariable();
    }

    final IntroduceParameterProcessor processor = new IntroduceParameterProcessor(
      myProject, myMethodToReplaceIn, myMethodToSearchFor,
      parameterInitializer, myExpression,
      myLocalVar, isDeleteLocalVariable,
      getParameterName(), myPanel.isReplaceAllOccurences(),
      myPanel.getReplaceFieldsWithGetters(), isDeclareFinal(), myPanel.isGenerateDelegate(), getSelectedType(), myPanel.getParametersToRemove());
    invokeRefactoring(processor);
    myParameterNameField.requestFocusInWindow();
  }


  private void updateFinalState() {
     if (myHasWriteAccess && myCbDeclareFinal != null) {
       myCbDeclareFinal.setEnabled(!myPanel.isReplaceAllOccurences());
       if (myPanel.isReplaceAllOccurences()) {
         myCbDeclareFinal.setSelected(false);
       }
     }
  }

  @Override
  protected void canRun() throws ConfigurationException {
    String name = getParameterName();
    if (name == null || !PsiNameHelper.getInstance(myProject).isIdentifier(name)) {
      throw new ConfigurationException("\'" + (name != null ? name : "") + "\' is invalid parameter name");
    }
  }

  public void setReplaceAllOccurrences(boolean replaceAllOccurrences) {
    myPanel.setReplaceAllOccurrences(replaceAllOccurrences);
  }

  public void setGenerateDelegate(boolean delegate) {
    myPanel.setGenerateDelegate(delegate);
  }

  private class IntroduceParameterSettingsPanel extends IntroduceParameterSettingsUI {
    public IntroduceParameterSettingsPanel(PsiLocalVariable onLocalVariable,
                                           PsiExpression onExpression,
                                           PsiMethod methodToReplaceIn, IntList parametersToRemove) {
      super(onLocalVariable, onExpression, methodToReplaceIn, parametersToRemove);
    }


    @Override
    protected TypeSelectorManager getTypeSelectionManager() {
      return myTypeSelectorManager;
    }
    @Override
    protected void updateControls(JCheckBox[] removeParamsCb) {
      super.updateControls(removeParamsCb);
      updateFinalState();
    }

    public void setGenerateDelegate(boolean delegate) {
      myCbGenerateDelegate.setSelected(delegate);
    }
  }
}
