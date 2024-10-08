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
package com.intellij.java.impl.refactoring.extractclass;

import com.intellij.java.impl.refactoring.HelpID;
import com.intellij.java.impl.refactoring.PackageWrapper;
import com.intellij.java.impl.refactoring.RefactorJBundle;
import com.intellij.java.impl.refactoring.move.moveClassesOrPackages.DestinationFolderComboBox;
import com.intellij.java.impl.refactoring.ui.JavaVisibilityPanel;
import com.intellij.java.impl.refactoring.ui.MemberSelectionPanel;
import com.intellij.java.impl.refactoring.ui.MemberSelectionTable;
import com.intellij.java.impl.refactoring.ui.PackageNameReferenceEditorCombo;
import com.intellij.java.impl.refactoring.util.classMembers.MemberInfo;
import com.intellij.java.impl.ui.ReferenceEditorComboWithBrowseButton;
import com.intellij.java.language.psi.*;
import consulo.application.HelpManager;
import consulo.configurable.ConfigurationException;
import consulo.language.editor.refactoring.classMember.DelegatingMemberInfoModel;
import consulo.language.editor.refactoring.classMember.MemberInfoBase;
import consulo.language.editor.refactoring.classMember.MemberInfoChange;
import consulo.language.editor.refactoring.classMember.MemberInfoChangeListener;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.ui.RefactoringDialog;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.psi.util.SymbolPresentationUtil;
import consulo.module.content.ProjectRootManager;
import consulo.project.Project;
import consulo.ui.ex.awt.FormBuilder;
import consulo.ui.ex.awt.JBLabelDecorator;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.ui.ex.awt.event.DocumentAdapter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.*;

@SuppressWarnings({"OverridableMethodCallInConstructor"})
class ExtractClassDialog extends RefactoringDialog implements MemberInfoChangeListener<PsiMember, MemberInfo> {
  private final Map<MemberInfoBase<PsiMember>, PsiMember> myMember2CauseMap = new HashMap<MemberInfoBase<PsiMember>, PsiMember>();
  private final PsiClass sourceClass;
  private final List<MemberInfo> memberInfo;
  private final JTextField classNameField;
  private final ReferenceEditorComboWithBrowseButton packageTextField;
  private final DestinationFolderComboBox myDestinationFolderComboBox;
  private final JTextField sourceClassTextField = null;
  private JCheckBox myGenerateAccessorsCb;
  private final JavaVisibilityPanel myVisibilityPanel;
  private final JCheckBox extractAsEnum;
  private final List<MemberInfo> enumConstants = new ArrayList<MemberInfo>();

  ExtractClassDialog(PsiClass sourceClass, PsiMember selectedMember) {
    super(sourceClass.getProject(), true);
    setModal(true);
    setTitle(RefactorJBundle.message("extract.class.title"));
    myVisibilityPanel = new JavaVisibilityPanel(true, true);
    myVisibilityPanel.setVisibility(null);
    this.sourceClass = sourceClass;
    final DocumentListener docListener = new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        validateButtons();
      }
    };
    classNameField = new JTextField();
    final PsiFile file = sourceClass.getContainingFile();
    final String text = file instanceof PsiJavaFile ? ((PsiJavaFile)file).getPackageName() : "";
    packageTextField = new PackageNameReferenceEditorCombo(text, myProject, "ExtractClass.RECENTS_KEY",
                                                           RefactorJBundle.message("choose.destination.package.label"));
    packageTextField.getChildComponent().getDocument().addDocumentListener(new consulo.document.event.DocumentAdapter() {
      @Override
      public void documentChanged(consulo.document.event.DocumentEvent e) {
        validateButtons();
      }
    });
    myDestinationFolderComboBox = new DestinationFolderComboBox() {
      @Override
      public String getTargetPackage() {
        return getPackageName();
      }
    };
    myDestinationFolderComboBox.setData(myProject, sourceClass.getContainingFile().getContainingDirectory(),
                                        packageTextField.getChildComponent());
    classNameField.getDocument().addDocumentListener(docListener);
    final MemberInfo.Filter<PsiMember> filter = new MemberInfo.Filter<PsiMember>() {
      public boolean includeMember(PsiMember element) {
        if (element instanceof PsiMethod) {
          return !((PsiMethod)element).isConstructor() && ((PsiMethod)element).getBody() != null;
        }
        else if (element instanceof PsiField) {
          return true;
        }
        else if (element instanceof PsiClass) {
          return PsiTreeUtil.isAncestor(ExtractClassDialog.this.sourceClass, element, true);
        }
        return false;
      }
    };
    memberInfo = MemberInfo.extractClassMembers(this.sourceClass, filter, false);
    extractAsEnum = new JCheckBox("Extract as enum");
    boolean hasConstants = false;
    for (MemberInfo info : memberInfo) {
      final PsiMember member = info.getMember();
      if (member.equals(selectedMember)) {
        info.setChecked(true);
      }
      if (!hasConstants &&
          member instanceof PsiField &&
          member.hasModifierProperty(PsiModifier.FINAL) &&
          member.hasModifierProperty(PsiModifier.STATIC)) {
        hasConstants = true;
      }
    }
    if (!hasConstants) {
      extractAsEnum.setVisible(false);
    }
    super.init();
    validateButtons();
  }

  protected void doAction() {

    final List<PsiField> fields = getFieldsToExtract();
    final List<PsiMethod> methods = getMethodsToExtract();
    final List<PsiClass> classes = getClassesToExtract();
    final String newClassName = getClassName();
    final String packageName = getPackageName();

    Collections.sort(enumConstants, new Comparator<MemberInfo>() {
      public int compare(MemberInfo o1, MemberInfo o2) {
        return o1.getMember().getTextOffset() - o2.getMember().getTextOffset();
      }
    });
    final ExtractClassProcessor processor = new ExtractClassProcessor(sourceClass, fields, methods, classes, packageName,
                                                                      myDestinationFolderComboBox.selectDirectory(
                                                                        new PackageWrapper(PsiManager.getInstance(myProject), packageName),
                                                                        false),
                                                                      newClassName, myVisibilityPanel.getVisibility(),
                                                                      isGenerateAccessors(),
                                                                      isExtractAsEnum()
                                                                      ? enumConstants
                                                                      : Collections.<MemberInfo>emptyList());
    if (processor.getCreatedClass() == null) {
      Messages.showErrorDialog(myVisibilityPanel, "Unable to create class with the given name");
      classNameField.requestFocusInWindow();
      return;
    }
    invokeRefactoring(processor);
  }

  @Override
  protected void canRun() throws ConfigurationException {
    final Project project = sourceClass.getProject();
    final PsiNameHelper nameHelper = PsiNameHelper.getInstance(project);
    final List<PsiMethod> methods = getMethodsToExtract();
    final List<PsiField> fields = getFieldsToExtract();
    final List<PsiClass> innerClasses = getClassesToExtract();
    if (methods.isEmpty() && fields.isEmpty() && innerClasses.isEmpty()) {
      throw new ConfigurationException("Nothing found to extract");
    }

    final String className = getClassName();
    if (className.length() == 0 || !nameHelper.isIdentifier(className)) {
      throw new ConfigurationException("\'" + className + "\' is invalid extracted class name");
    }

    /*final String packageName = getPackageName();
    if (packageName.length() == 0 || !nameHelper.isQualifiedName(packageName)) {
      throw new ConfigurationException("\'" + packageName + "\' is invalid extracted class package name");
    }*/
    for (PsiClass innerClass : innerClasses) {
      if (className.equals(innerClass.getName())) {
        throw new ConfigurationException(
          "Extracted class should have unique name. Name " + "\'" + className + "\' is already in use by one of the inner classes");
      }
    }
  }

  @Nonnull
  public String getPackageName() {
    return packageTextField.getText().trim();
  }

  @Nonnull
  public String getClassName() {
    return classNameField.getText().trim();
  }

  public List<PsiField> getFieldsToExtract() {
    return getMembersToExtract(true, PsiField.class);
  }

  public <T> List<T> getMembersToExtract(final boolean checked, Class<T> memberClass) {
    final List<T> out = new ArrayList<T>();
    for (MemberInfo info : memberInfo) {
      if (checked && !info.isChecked()) continue;
      if (!checked && info.isChecked()) continue;
      final PsiMember member = info.getMember();
      if (memberClass.isAssignableFrom(member.getClass())) {
        out.add((T)member);
      }
    }
    return out;
  }

  public List<PsiMethod> getMethodsToExtract() {
    return getMembersToExtract(true, PsiMethod.class);
  }

  public List<PsiClass> getClassesToExtract() {
    return getMembersToExtract(true, PsiClass.class);
  }

  public List<PsiClassInitializer> getClassInitializersToExtract() {
    return getMembersToExtract(true, PsiClassInitializer.class);
  }

  public boolean isGenerateAccessors() {
    return myGenerateAccessorsCb.isSelected();
  }

  public boolean isExtractAsEnum() {
    return extractAsEnum.isVisible() && extractAsEnum.isEnabled() && extractAsEnum.isSelected();
  }

  protected String getDimensionServiceKey() {
    return "RefactorJ.ExtractClass";
  }

  protected JComponent createNorthPanel() {
    FormBuilder builder = FormBuilder.createFormBuilder()
      .addComponent(
        JBLabelDecorator.createJBLabelDecorator(RefactorJBundle.message("extract.class.from.label", sourceClass.getQualifiedName()))
          .setBold(true))
      .addLabeledComponent(RefactorJBundle.message("name.for.new.class.label"), classNameField, UIUtil.LARGE_VGAP)
      .addLabeledComponent(new JLabel(), extractAsEnum)
      .addLabeledComponent(RefactorJBundle.message("package.for.new.class.label"), packageTextField);

    if (ProjectRootManager.getInstance(myProject).getContentSourceRoots().length > 1) {
      builder.addLabeledComponent(RefactoringLocalize.targetDestinationFolder().get(), myDestinationFolderComboBox);
    }

    return builder.addVerticalGap(5).getPanel();
  }

  protected JComponent createCenterPanel() {
    final JPanel panel = new JPanel(new BorderLayout());
    final MemberSelectionPanel memberSelectionPanel =
      new MemberSelectionPanel(RefactorJBundle.message("members.to.extract.label"), memberInfo, "As enum") {
        @Override
        protected MemberSelectionTable createMemberSelectionTable(final List<MemberInfo> memberInfo, String abstractColumnHeader) {
          return new MemberSelectionTable(memberInfo, abstractColumnHeader) {
            @Nullable
            @Override
            protected Object getAbstractColumnValue(MemberInfo memberInfo) {
              if (isExtractAsEnum()) {
                final PsiMember member = memberInfo.getMember();
                if (isConstantField(member)) {
                  return Boolean.valueOf(enumConstants.contains(memberInfo));
                }
              }
              return null;
            }

            @Override
            protected boolean isAbstractColumnEditable(int rowIndex) {
              final MemberInfo info = memberInfo.get(rowIndex);
              if (info.isChecked()) {
                final PsiMember member = info.getMember();
                if (isConstantField(member)) {
                  if (enumConstants.isEmpty()) return true;
                  final MemberInfo currentEnumConstant = enumConstants.get(0);
                  if (((PsiField)currentEnumConstant.getMember()).getType().equals(((PsiField)member).getType())) return true;
                }
              }
              return false;
            }
          };
        }
      };
    final MemberSelectionTable table = memberSelectionPanel.getTable();
    table.setMemberInfoModel(new DelegatingMemberInfoModel<PsiMember, MemberInfo>(table.getMemberInfoModel()) {

      @Override
      public int checkForProblems(@Nonnull final MemberInfo member) {
        final PsiMember cause = getCause(member);
        if (member.isChecked() && cause != null) return ERROR;
        if (!member.isChecked() && cause != null) return WARNING;
        return OK;
      }

      @Override
      public String getTooltipText(final MemberInfo member) {
        final PsiMember cause = getCause(member);
        if (cause != null) {
          final String presentation = SymbolPresentationUtil.getSymbolPresentableText(cause);
          if (member.isChecked()) {
            return "Depends on " + presentation + " from " + sourceClass.getName();
          }
          else {
            final String className = getClassName();
            return "Depends on " + presentation + " from new class" + (className.length() > 0 ? ": " + className : "");
          }
        }
        return null;
      }

      private PsiMember getCause(final MemberInfo member) {
        PsiMember cause = myMember2CauseMap.get(member);

        if (cause != null) return cause;

        final BackpointerUsageVisitor visitor;
        if (member.isChecked()) {
          visitor = new BackpointerUsageVisitor(getFieldsToExtract(), getClassesToExtract(), getMethodsToExtract(), sourceClass);
        }
        else {
          visitor =
            new BackpointerUsageVisitor(getMembersToExtract(false, PsiField.class), getMembersToExtract(false, PsiClass.class),
                                        getMembersToExtract(false, PsiMethod.class), sourceClass, false);
        }

        member.getMember().accept(visitor);
        cause = visitor.getCause();
        myMember2CauseMap.put(member, cause);
        return cause;
      }
    });
    panel.add(memberSelectionPanel, BorderLayout.CENTER);
    table.addMemberInfoChangeListener(this);
    extractAsEnum.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        if (extractAsEnum.isSelected()) {
          preselectOneTypeEnumConstants();
        }
        table.repaint();
      }
    });
    myGenerateAccessorsCb = new JCheckBox("Generate accessors");
    myGenerateAccessorsCb.setMnemonic('G');
    panel.add(myGenerateAccessorsCb, BorderLayout.SOUTH);

    panel.add(myVisibilityPanel, BorderLayout.EAST);
    return panel;
  }

  private void preselectOneTypeEnumConstants() {
    if (enumConstants.isEmpty()) {
      MemberInfo selected = null;
      for (MemberInfo info : memberInfo) {
        if (info.isChecked()) {
          selected = info;
          break;
        }
      }
      if (selected != null && isConstantField(selected.getMember())) {
        enumConstants.add(selected);
        selected.setToAbstract(true);
      }
    }
    for (MemberInfo info : memberInfo) {
      final PsiMember member = info.getMember();
      if (isConstantField(member)) {
        if (enumConstants.isEmpty() || ((PsiField)enumConstants.get(0).getMember()).getType().equals(((PsiField)member).getType())) {
          if (!enumConstants.contains(info)) enumConstants.add(info);
          info.setToAbstract(true);
        }
      }
    }
  }

  private static boolean isConstantField(PsiMember member) {
    return member instanceof PsiField &&
           member.hasModifierProperty(PsiModifier.STATIC) &&
           // member.hasModifierProperty(PsiModifier.FINAL) &&
           ((PsiField)member).hasInitializer();
  }

  public JComponent getPreferredFocusedComponent() {
    return classNameField;
  }

  protected void doHelpAction() {
    final HelpManager helpManager = HelpManager.getInstance();
    helpManager.invokeHelp(HelpID.ExtractClass);
  }

  public void memberInfoChanged(MemberInfoChange memberInfoChange) {
    validateButtons();
    myMember2CauseMap.clear();
    if (extractAsEnum.isVisible()) {
      for (Object info : memberInfoChange.getChangedMembers()) {
        if (((MemberInfo)info).isToAbstract()) {
          if (!enumConstants.contains(info)) {
            enumConstants.add((MemberInfo)info);
          }
        }
        else {
          enumConstants.remove((MemberInfo)info);
        }
      }
      extractAsEnum.setEnabled(canExtractEnum());
    }
  }

  private boolean canExtractEnum() {
    final List<PsiField> fields = new ArrayList<PsiField>();
    final List<PsiClass> innerClasses = new ArrayList<PsiClass>();
    final List<PsiMethod> methods = new ArrayList<PsiMethod>();
    for (MemberInfo info : memberInfo) {
      if (info.isChecked()) {
        final PsiMember member = info.getMember();
        if (member instanceof PsiField) {
          fields.add((PsiField)member);
        }
        else if (member instanceof PsiMethod) {
          methods.add((PsiMethod)member);
        }
        else if (member instanceof PsiClass) {
          innerClasses.add((PsiClass)member);
        }
      }
    }
    return !new BackpointerUsageVisitor(fields, innerClasses, methods, sourceClass).backpointerRequired();
  }
}
