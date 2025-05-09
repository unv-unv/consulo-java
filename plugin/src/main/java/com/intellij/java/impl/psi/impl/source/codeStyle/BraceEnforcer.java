/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.java.impl.psi.impl.source.codeStyle;

import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.psi.PsiElementFactory;
import com.intellij.java.language.psi.*;
import consulo.document.util.TextRange;
import consulo.language.codeStyle.CodeStyleManager;
import consulo.language.codeStyle.CodeStyleSettings;
import consulo.language.codeStyle.CommonCodeStyleSettings;
import consulo.language.codeStyle.PostFormatProcessorHelper;
import consulo.language.impl.psi.CodeEditUtil;
import consulo.language.impl.psi.SourceTreeToPsiMap;
import consulo.language.psi.*;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import jakarta.annotation.Nonnull;

/**
 * @author max
 */
public class BraceEnforcer extends JavaRecursiveElementVisitor {
  private static final Logger LOG = Logger.getInstance(BraceEnforcer.class);

  private final PostFormatProcessorHelper myPostProcessor;

  public BraceEnforcer(CodeStyleSettings settings) {
    myPostProcessor = new PostFormatProcessorHelper(settings.getCommonSettings(JavaLanguage.INSTANCE));
  }

  @Override
  public void visitReferenceExpression(PsiReferenceExpression expression) {
    visitElement(expression);
  }

  @Override
  public void visitIfStatement(PsiIfStatement statement) {
    if (checkElementContainsRange(statement)) {
      final SmartPsiElementPointer pointer = SmartPointerManager.getInstance(statement.getProject()).createSmartPsiElementPointer(statement);
      super.visitIfStatement(statement);
      statement = (PsiIfStatement) pointer.getElement();
      if (statement == null) {
        return;
      }
      processStatement(statement, statement.getThenBranch(), myPostProcessor.getSettings().IF_BRACE_FORCE);
      final PsiStatement elseBranch = statement.getElseBranch();
      if (!(elseBranch instanceof PsiIfStatement) || !myPostProcessor.getSettings().SPECIAL_ELSE_IF_TREATMENT) {
        processStatement(statement, elseBranch, myPostProcessor.getSettings().IF_BRACE_FORCE);
      }
    }
  }

  @Override
  public void visitForStatement(PsiForStatement statement) {
    if (checkElementContainsRange(statement)) {
      super.visitForStatement(statement);
      processStatement(statement, statement.getBody(), myPostProcessor.getSettings().FOR_BRACE_FORCE);
    }
  }

  @Override
  public void visitForeachStatement(PsiForeachStatement statement) {
    if (checkElementContainsRange(statement)) {
      super.visitForeachStatement(statement);
      processStatement(statement, statement.getBody(), myPostProcessor.getSettings().FOR_BRACE_FORCE);
    }
  }

  @Override
  public void visitWhileStatement(PsiWhileStatement statement) {
    if (checkElementContainsRange(statement)) {
      super.visitWhileStatement(statement);
      processStatement(statement, statement.getBody(), myPostProcessor.getSettings().WHILE_BRACE_FORCE);
    }
  }

  @Override
  public void visitDoWhileStatement(PsiDoWhileStatement statement) {
    if (checkElementContainsRange(statement)) {
      super.visitDoWhileStatement(statement);
      processStatement(statement, statement.getBody(), myPostProcessor.getSettings().DOWHILE_BRACE_FORCE);
    }
  }

	/*@Override
  public void visitJspFile(JspFile file)
	{
		final PsiClass javaRoot = file.getJavaClass();
		if(javaRoot != null)
		{
			javaRoot.accept(this);
		}
	} */

  private void processStatement(PsiStatement statement, PsiStatement blockCandidate, int options) {
    if (blockCandidate instanceof PsiBlockStatement || blockCandidate == null) {
      return;
    }
    if (options == CommonCodeStyleSettings.FORCE_BRACES_ALWAYS
        || (options == CommonCodeStyleSettings.FORCE_BRACES_IF_MULTILINE && PostFormatProcessorHelper.isMultiline(statement))) {
      replaceWithBlock(statement, blockCandidate);
    }
  }

  private void replaceWithBlock(@Nonnull PsiStatement statement, PsiStatement blockCandidate) {
    if (!statement.isValid()) {
      LOG.assertTrue(false);
    }

    if (!checkRangeContainsElement(blockCandidate)) {
      return;
    }

    final PsiManager manager = statement.getManager();
    LOG.assertTrue(manager != null);
    final PsiElementFactory factory = JavaPsiFacade.getInstance(manager.getProject()).getElementFactory();

    String oldText = blockCandidate.getText();
    // There is a possible case that target block to wrap ends with single-line comment. Example:
    //     if (true) i = 1; // Cool assignment
    // We can't just surround target block of code with curly braces because the closing one will be treated as comment as well.
    // Hence, we perform a check if we have such situation at the moment and insert new line before the closing brace.
    int lastLineFeedIndex = oldText.lastIndexOf("\n");
    lastLineFeedIndex = Math.max(0, lastLineFeedIndex);
    int lastLineCommentIndex = oldText.indexOf("//", lastLineFeedIndex);
    StringBuilder buf = new StringBuilder(oldText.length() + 5);
    buf.append("{ ").append(oldText);
    if (lastLineCommentIndex >= 0) {
      buf.append("\n");
    }
    buf.append(" }");
    final int oldTextLength = statement.getTextLength();
    try {
      CodeEditUtil.replaceChild(SourceTreeToPsiMap.psiElementToTree(statement),
          SourceTreeToPsiMap.psiElementToTree(blockCandidate),
          SourceTreeToPsiMap.psiElementToTree(factory.createStatementFromText(buf.toString(), null)));
      CodeStyleManager.getInstance(statement.getProject()).reformat(statement, true);
    } catch (IncorrectOperationException e) {
      LOG.error(e);
    } finally {
      updateResultRange(oldTextLength, statement.getTextLength());
    }
  }

  protected void updateResultRange(final int oldTextLength, final int newTextLength) {
    myPostProcessor.updateResultRange(oldTextLength, newTextLength);
  }

  protected boolean checkElementContainsRange(final PsiElement element) {
    return myPostProcessor.isElementPartlyInRange(element);
  }

  protected boolean checkRangeContainsElement(final PsiElement element) {
    return myPostProcessor.isElementFullyInRange(element);
  }

  public PsiElement process(PsiElement formatted) {
    LOG.assertTrue(formatted.isValid());
    formatted.accept(this);
    return formatted;

  }

  public TextRange processText(final PsiFile source, final TextRange rangeToReformat) {
    myPostProcessor.setResultTextRange(rangeToReformat);
    source.accept(this);
    return myPostProcessor.getResultTextRange();
  }
}
