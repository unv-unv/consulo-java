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
package com.intellij.java.impl.codeInsight.daemon.impl.quickfix;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nonnull;

import consulo.logging.Logger;
import jakarta.annotation.Nullable;
import org.jetbrains.annotations.NonNls;
import consulo.language.editor.FileModificationService;
import com.intellij.java.impl.codeInsight.generation.GenerateMembersUtil;
import consulo.language.editor.inspection.LocalQuickFixAndIntentionActionOnPsiElement;
import consulo.codeEditor.Editor;
import consulo.project.Project;
import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiCodeBlock;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import com.intellij.java.language.psi.PsiMethod;
import consulo.language.codeStyle.CodeStyleManager;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.util.MethodSignatureUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.util.collection.ContainerUtil;
import consulo.java.analysis.impl.JavaQuickFixBundle;

public class AddMethodFix extends LocalQuickFixAndIntentionActionOnPsiElement {
  private static final Logger LOG = Logger.getInstance(AddMethodFix.class);

  private final PsiMethod myMethodPrototype;
  private String myText;
  private final List<String> myExceptions = new ArrayList<String>();

  public AddMethodFix(@Nonnull PsiMethod methodPrototype, @Nonnull PsiClass implClass) {
    super(implClass);
    myMethodPrototype = methodPrototype;
    setText(JavaQuickFixBundle.message("add.method.text", methodPrototype.getName(), implClass.getName()));
  }

  public AddMethodFix(@NonNls @Nonnull String methodText, @Nonnull PsiClass implClass, @Nonnull String... exceptions) {
    this(createMethod(methodText, implClass), implClass);
    ContainerUtil.addAll(myExceptions, exceptions);
  }

  private static PsiMethod createMethod(final String methodText, final PsiClass implClass) {
    try {
      return JavaPsiFacade.getInstance(implClass.getProject()).getElementFactory().createMethodFromText(methodText, implClass);
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
      return null;
    }
  }

  private static PsiMethod reformat(Project project, PsiMethod result) throws IncorrectOperationException {
    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
    result = (PsiMethod) codeStyleManager.reformat(result);

    JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
    result = (PsiMethod) javaCodeStyleManager.shortenClassReferences(result);
    return result;
  }

  protected void setText(@Nonnull String text) {
    myText = text;
  }

  @Nonnull
  @Override
  public String getText() {
    return myText;
  }

  @Override
  @Nonnull
  public String getFamilyName() {
    return JavaQuickFixBundle.message("add.method.family");
  }

  @Override
  public boolean isAvailable(@Nonnull Project project,
                             @Nonnull PsiFile file,
                             @Nonnull PsiElement startElement,
                             @Nonnull PsiElement endElement) {
    final PsiClass myClass = (PsiClass)startElement;

    return myMethodPrototype != null
           && myMethodPrototype.isValid()
           && myClass.isValid()
           && myClass.getManager().isInProject(myClass)
           && myText != null
           && MethodSignatureUtil.findMethodBySignature(myClass, myMethodPrototype, false) == null
        ;
  }

  @Override
  public void invoke(@Nonnull Project project,
                     @Nonnull PsiFile file,
                     @Nullable Editor editor,
                     @Nonnull PsiElement startElement,
                     @Nonnull PsiElement endElement) {
    final PsiClass myClass = (PsiClass)startElement;
    if (!FileModificationService.getInstance().prepareFileForWrite(myClass.getContainingFile())) return;
    PsiCodeBlock body;
    if (myClass.isInterface() && (body = myMethodPrototype.getBody()) != null) body.delete();
    for (String exception : myExceptions) {
      PsiUtil.addException(myMethodPrototype, exception);
    }
    PsiMethod method = (PsiMethod)myClass.add(myMethodPrototype);
    method = (PsiMethod)method.replace(reformat(project, method));
    if (editor != null && method.getContainingFile() == file) {
      GenerateMembersUtil.positionCaret(editor, method, true);
    }
  }
}
