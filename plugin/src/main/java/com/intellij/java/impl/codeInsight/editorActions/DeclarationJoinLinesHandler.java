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
package com.intellij.java.impl.codeInsight.editorActions;

import com.intellij.java.language.psi.PsiElementFactory;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.document.Document;
import consulo.language.ast.IElementType;
import consulo.language.codeStyle.CodeStyleManager;
import consulo.language.editor.action.JoinLinesHandlerDelegate;
import consulo.language.psi.*;
import consulo.language.psi.scope.LocalSearchScope;
import consulo.language.psi.search.ReferencesSearch;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;

@ExtensionImpl
public class DeclarationJoinLinesHandler implements JoinLinesHandlerDelegate {
  private static final Logger LOG = Logger.getInstance(DeclarationJoinLinesHandler.class);

  @Override
  public int tryJoinLines(final Document document, final PsiFile file, final int start, final int end) {
    PsiElement elementAtStartLineEnd = file.findElementAt(start);
    PsiElement elementAtNextLineStart = file.findElementAt(end);
    if (elementAtStartLineEnd == null || elementAtNextLineStart == null) return -1;

    // first line.
    if (!(elementAtStartLineEnd instanceof PsiJavaToken)) return -1;
    PsiJavaToken lastFirstLineToken = (PsiJavaToken) elementAtStartLineEnd;
    if (lastFirstLineToken.getTokenType() != JavaTokenType.SEMICOLON) return -1;
    if (!(lastFirstLineToken.getParent() instanceof PsiLocalVariable)) return -1;
    PsiLocalVariable var = (PsiLocalVariable) lastFirstLineToken.getParent();

    if (!(var.getParent() instanceof PsiDeclarationStatement)) return -1;
    PsiDeclarationStatement decl = (PsiDeclarationStatement) var.getParent();
    if (decl.getDeclaredElements().length > 1) return -1;

    //second line.
    if (!(elementAtNextLineStart instanceof PsiJavaToken)) return -1;
    PsiJavaToken firstNextLineToken = (PsiJavaToken) elementAtNextLineStart;
    if (firstNextLineToken.getTokenType() != JavaTokenType.IDENTIFIER) return -1;
    if (!(firstNextLineToken.getParent() instanceof PsiReferenceExpression)) return -1;
    PsiReferenceExpression ref = (PsiReferenceExpression) firstNextLineToken.getParent();
    PsiElement refResolved = ref.resolve();

    PsiManager psiManager = ref.getManager();
    if (!psiManager.areElementsEquivalent(refResolved, var)) return -1;
    if (!(ref.getParent() instanceof PsiAssignmentExpression)) return -1;
    PsiAssignmentExpression assignment = (PsiAssignmentExpression) ref.getParent();
    if (!(assignment.getParent() instanceof PsiExpressionStatement)) return -1;

    if (ReferencesSearch.search(var, new LocalSearchScope(assignment.getRExpression()), false).findFirst() != null) {
      return -1;
    }

    final PsiElementFactory factory = JavaPsiFacade.getInstance(psiManager.getProject()).getElementFactory();
    PsiExpression initializerExpression;
    final IElementType originalOpSign = assignment.getOperationTokenType();
    if (originalOpSign == JavaTokenType.EQ) {
      initializerExpression = assignment.getRExpression();
    } else {
      if (var.getInitializer() == null) return -1;
      String opSign = null;
      if (originalOpSign == JavaTokenType.ANDEQ) {
        opSign = "&";
      } else if (originalOpSign == JavaTokenType.ASTERISKEQ) {
        opSign = "*";
      } else if (originalOpSign == JavaTokenType.DIVEQ) {
        opSign = "/";
      } else if (originalOpSign == JavaTokenType.GTGTEQ) {
        opSign = ">>";
      } else if (originalOpSign == JavaTokenType.GTGTGTEQ) {
        opSign = ">>>";
      } else if (originalOpSign == JavaTokenType.LTLTEQ) {
        opSign = "<<";
      } else if (originalOpSign == JavaTokenType.MINUSEQ) {
        opSign = "-";
      } else if (originalOpSign == JavaTokenType.OREQ) {
        opSign = "|";
      } else if (originalOpSign == JavaTokenType.PERCEQ) {
        opSign = "%";
      } else if (originalOpSign == JavaTokenType.PLUSEQ) {
        opSign = "+";
      } else if (originalOpSign == JavaTokenType.XOREQ) {
        opSign = "^";
      }

      try {
        initializerExpression =
            factory.createExpressionFromText(var.getInitializer().getText() + opSign + assignment.getRExpression().getText(), var);
        initializerExpression = (PsiExpression) CodeStyleManager.getInstance(psiManager).reformat(initializerExpression);
      } catch (IncorrectOperationException e) {
        LOG.error(e);
        return -1;
      }
    }

    PsiExpressionStatement statement = (PsiExpressionStatement) assignment.getParent();

    int startOffset = decl.getTextRange().getStartOffset();
    try {
      PsiDeclarationStatement newDecl = factory.createVariableDeclarationStatement(var.getName(), var.getType(), initializerExpression);
      PsiVariable newVar = (PsiVariable) newDecl.getDeclaredElements()[0];
      if (var.getModifierList().getText().length() > 0) {
        PsiUtil.setModifierProperty(newVar, PsiModifier.FINAL, true);
      }
      newVar.getModifierList().replace(var.getModifierList());
      PsiVariable variable = (PsiVariable) newDecl.getDeclaredElements()[0];
      final int offsetBeforeEQ = variable.getNameIdentifier().getTextRange().getEndOffset();
      final int offsetAfterEQ = variable.getInitializer().getTextRange().getStartOffset() + 1;
      newDecl = (PsiDeclarationStatement) CodeStyleManager.getInstance(psiManager).reformatRange(newDecl, offsetBeforeEQ, offsetAfterEQ);

      PsiElement child = statement.getLastChild();
      while (child instanceof PsiComment || child instanceof PsiWhiteSpace) {
        child = child.getPrevSibling();
      }
      if (child != null && child.getNextSibling() != null) {
        newDecl.addRangeBefore(child.getNextSibling(), statement.getLastChild(), null);
      }

      decl.replace(newDecl);
      statement.delete();
      return startOffset + newDecl.getTextRange().getEndOffset() - newDecl.getTextRange().getStartOffset();
    } catch (IncorrectOperationException e) {
      LOG.error(e);
      return -1;
    }
  }
}
