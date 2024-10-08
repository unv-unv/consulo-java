/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.AutoPopupController;
import consulo.language.editor.CodeInsightSettings;
import consulo.ide.impl.idea.codeInsight.editorActions.TypedHandler;
import consulo.language.editor.action.TypedHandlerDelegate;
import com.intellij.java.impl.codeInsight.completion.JavaClassReferenceCompletionContributor;
import com.intellij.java.impl.codeInsight.editorActions.smartEnter.JavaSmartEnterProcessor;
import com.intellij.java.language.impl.JavaFileType;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.javadoc.PsiDocComment;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.undoRedo.CommandProcessor;
import consulo.document.Document;
import consulo.codeEditor.Editor;
import consulo.ide.impl.idea.openapi.editor.EditorModificationUtil;
import consulo.codeEditor.EditorEx;
import consulo.codeEditor.HighlighterIterator;
import consulo.virtualFileSystem.fileType.FileType;
import consulo.project.Project;
import consulo.util.lang.function.Condition;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.ast.TokenType;
import consulo.language.codeStyle.CodeStyleManager;
import consulo.language.ast.IElementType;
import consulo.language.ast.TokenSet;
import consulo.language.psi.util.PsiTreeUtil;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * @author yole
 */
@ExtensionImpl(id = "java")
public class JavaTypedHandler extends TypedHandlerDelegate {
  static final TokenSet INVALID_INSIDE_REFERENCE = TokenSet.create(JavaTokenType.SEMICOLON, JavaTokenType.LBRACE,
      JavaTokenType.RBRACE);
  private boolean myJavaLTTyped;

  private static void autoPopupMemberLookup(Project project, final Editor editor) {
    AutoPopupController.getInstance(project).autoPopupMemberLookup(editor, new Condition<PsiFile>() {
      @Override
      public boolean value(final PsiFile file) {
        int offset = editor.getCaretModel().getOffset();

        PsiElement lastElement = file.findElementAt(offset - 1);
        if (lastElement == null) {
          return false;
        }

        //do not show lookup when typing varargs ellipsis
        final PsiElement prevSibling = PsiTreeUtil.prevVisibleLeaf(lastElement);
        if (prevSibling == null || ".".equals(prevSibling.getText())) {
          return false;
        }
        PsiElement parent = prevSibling;
        do {
          parent = parent.getParent();
        }
        while (parent instanceof PsiJavaCodeReferenceElement || parent instanceof PsiTypeElement);
        if (parent instanceof PsiParameterList || parent instanceof PsiParameter) {
          return false;
        }

        if (!".".equals(lastElement.getText()) && !"#".equals(lastElement.getText())) {
          return JavaClassReferenceCompletionContributor.findJavaClassReference(file, offset - 1) != null;
        } else {
          final PsiElement element = file.findElementAt(offset);
          return element == null ||
              !"#".equals(lastElement.getText()) ||
              PsiTreeUtil.getParentOfType(element, PsiDocComment.class) != null;
        }
      }
    });
  }

  @Override
  public Result beforeCharTyped(final char c,
                                final Project project,
                                final Editor editor,
                                final PsiFile file,
                                final FileType fileType) {
    if (c == '@' && file instanceof PsiJavaFile) {
      autoPopupJavadocLookup(project, editor);
    } else if (c == '#' || c == '.') {
      autoPopupMemberLookup(project, editor);
    }


    final FileType originalFileType = getOriginalFileType(file);

    int offsetBefore = editor.getCaretModel().getOffset();

    //important to calculate before inserting charTyped
    myJavaLTTyped = '<' == c &&
        file instanceof PsiJavaFile &&
        // !(file instanceof JspFile) &&
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET &&
        PsiUtil.isLanguageLevel5OrHigher(file) &&
        isAfterClassLikeIdentifierOrDot(offsetBefore, editor);

    if ('>' == c) {
      if (file instanceof PsiJavaFile/* && !(file instanceof JspFile)*/ &&
          CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET &&
          PsiUtil.isLanguageLevel5OrHigher(file)) {
        if (handleJavaGT(editor, JavaTokenType.LT, JavaTokenType.GT, INVALID_INSIDE_REFERENCE)) {
          return Result.STOP;
        }
      }
    }

    if (c == ';') {
      if (handleSemicolon(editor, fileType)) {
        return Result.STOP;
      }
    }
    if (originalFileType == JavaFileType.INSTANCE && c == '{') {
      int offset = editor.getCaretModel().getOffset();
      if (offset == 0) {
        return Result.CONTINUE;
      }

      HighlighterIterator iterator = ((EditorEx) editor).getHighlighter().createIterator(offset - 1);
      while (!iterator.atEnd() && iterator.getTokenType() == TokenType.WHITE_SPACE) {
        iterator.retreat();
      }
      if (iterator.atEnd() || iterator.getTokenType() == JavaTokenType.RBRACKET || iterator.getTokenType() ==
          JavaTokenType.EQ) {
        return Result.CONTINUE;
      }
      Document doc = editor.getDocument();
      PsiDocumentManager.getInstance(project).commitDocument(doc);
      final PsiElement leaf = file.findElementAt(offset);
      if (PsiTreeUtil.getParentOfType(leaf, PsiArrayInitializerExpression.class, false, PsiCodeBlock.class,
          PsiMember.class) != null) {
        return Result.CONTINUE;
      }
      PsiElement st = leaf != null ? leaf.getParent() : null;
      PsiElement prev = offset > 1 ? file.findElementAt(offset - 1) : null;
      if (CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET && isRparenth(leaf) &&
          (st instanceof PsiWhileStatement || st instanceof PsiIfStatement) && shouldInsertStatementBody(st,
          doc, prev)) {
        CommandProcessor.getInstance().executeCommand(project, new Runnable() {
          @Override
          public void run() {
            new JavaSmartEnterProcessor().process(project, editor, file);
          }
        }, "Insert block statement", null);
        return Result.STOP;
      }
      if (PsiTreeUtil.getParentOfType(leaf, PsiCodeBlock.class, false, PsiMember.class) != null) {
        EditorModificationUtil.insertStringAtCaret(editor, "{");
        TypedHandler.indentOpenedBrace(project, editor);
        return Result.STOP;
      }
    }

    return Result.CONTINUE;
  }

  private static boolean shouldInsertStatementBody(@Nonnull PsiElement statement,
                                                   @Nonnull Document doc,
                                                   @Nullable PsiElement prev) {
    PsiStatement block = statement instanceof PsiWhileStatement ? ((PsiWhileStatement) statement).getBody() : (
        (PsiIfStatement) statement).getThenBranch();
    PsiExpression condition = PsiTreeUtil.getChildOfType(statement, PsiExpression.class);
    PsiExpression latestExpression = PsiTreeUtil.getParentOfType(prev, PsiExpression.class);
    if (latestExpression instanceof PsiNewExpression && ((PsiNewExpression) latestExpression).getAnonymousClass() == null) {
      return false;
    }
    return !(block instanceof PsiBlockStatement) && (block == null || startLine(doc, block) != startLine(doc,
        statement) || condition == null);
  }

  private static boolean isRparenth(@Nullable PsiElement leaf) {
    if (leaf == null) {
      return false;
    }
    if (leaf.getNode().getElementType() == JavaTokenType.RPARENTH) {
      return true;
    }
    PsiElement next = PsiTreeUtil.nextVisibleLeaf(leaf);
    if (next == null) {
      return false;
    }
    return next.getNode().getElementType() == JavaTokenType.RPARENTH;
  }

  private static int startLine(@Nonnull Document doc, @Nonnull PsiElement psiElement) {
    return doc.getLineNumber(psiElement.getTextRange().getStartOffset());
  }

  @Override
  public Result charTyped(final char c,
                          final Project project,
                          @Nonnull final Editor editor,
                          @Nonnull final PsiFile file) {
    if (myJavaLTTyped) {
      myJavaLTTyped = false;
      handleAfterJavaLT(editor, JavaTokenType.LT, JavaTokenType.GT, INVALID_INSIDE_REFERENCE);
      return Result.STOP;
    } else if (c == ':') {
      if (autoIndentCase(editor, project, file)) {
        return Result.STOP;
      }
    }
    return Result.CONTINUE;
  }

  @Nullable
  private static FileType getOriginalFileType(final PsiFile file) {
    final VirtualFile virtualFile = file.getVirtualFile();
    return virtualFile != null ? virtualFile.getFileType() : null;
  }

  private static boolean handleSemicolon(Editor editor, FileType fileType) {
    if (fileType != JavaFileType.INSTANCE) {
      return false;
    }
    int offset = editor.getCaretModel().getOffset();
    if (offset == editor.getDocument().getTextLength()) {
      return false;
    }

    char charAt = editor.getDocument().getCharsSequence().charAt(offset);
    if (charAt != ';') {
      return false;
    }

    EditorModificationUtil.moveCaretRelatively(editor, 1);
    return true;
  }

  //need custom handler, since brace matcher cannot be used
  public static boolean handleJavaGT(final Editor editor,
                                     final IElementType lt,
                                     final IElementType gt,
                                     final TokenSet invalidInsideReference) {
    if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
      return false;
    }

    int offset = editor.getCaretModel().getOffset();

    if (offset == editor.getDocument().getTextLength()) {
      return false;
    }

    HighlighterIterator iterator = ((EditorEx) editor).getHighlighter().createIterator(offset);
    if (iterator.getTokenType() != gt) {
      return false;
    }
    while (!iterator.atEnd() && !invalidInsideReference.contains((IElementType) iterator.getTokenType())) {
      iterator.advance();
    }

    if (!iterator.atEnd() && invalidInsideReference.contains((IElementType) iterator.getTokenType())) {
      iterator.retreat();
    }

    int balance = 0;
    while (!iterator.atEnd() && balance >= 0) {
      final IElementType tokenType = (IElementType) iterator.getTokenType();
      if (tokenType == lt) {
        balance--;
      } else if (tokenType == gt) {
        balance++;
      } else if (invalidInsideReference.contains(tokenType)) {
        break;
      }

      iterator.retreat();
    }

    if (balance == 0) {
      EditorModificationUtil.moveCaretRelatively(editor, 1);
      return true;
    }

    return false;
  }

  //need custom handler, since brace matcher cannot be used
  public static void handleAfterJavaLT(final Editor editor,
                                       final IElementType lt,
                                       final IElementType gt,
                                       final TokenSet invalidInsideReference) {
    if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
      return;
    }

    int offset = editor.getCaretModel().getOffset();
    HighlighterIterator iterator = ((EditorEx) editor).getHighlighter().createIterator(offset);
    while (iterator.getStart() > 0 && !invalidInsideReference.contains((IElementType) iterator.getTokenType())) {
      iterator.retreat();
    }

    if (invalidInsideReference.contains((IElementType) iterator.getTokenType())) {
      iterator.advance();
    }

    int balance = 0;
    while (!iterator.atEnd() && balance >= 0) {
      final IElementType tokenType = (IElementType) iterator.getTokenType();
      if (tokenType == lt) {
        balance++;
      } else if (tokenType == gt) {
        balance--;
      } else if (invalidInsideReference.contains(tokenType)) {
        break;
      }

      iterator.advance();
    }

    if (balance == 1) {
      editor.getDocument().insertString(offset, ">");
    }
  }

  private static void autoPopupJavadocLookup(final Project project, final Editor editor) {
    AutoPopupController.getInstance(project).autoPopupMemberLookup(editor, new Condition<PsiFile>() {
      @Override
      public boolean value(PsiFile file) {
        int offset = editor.getCaretModel().getOffset();

        PsiElement lastElement = file.findElementAt(offset - 1);
        return lastElement != null && StringUtil.endsWithChar(lastElement.getText(), '@');
      }
    });
  }

  public static boolean isAfterClassLikeIdentifierOrDot(final int offset, final Editor editor) {
    HighlighterIterator iterator = ((EditorEx) editor).getHighlighter().createIterator(offset);
    if (iterator.atEnd()) {
      return false;
    }
    if (iterator.getStart() > 0) {
      iterator.retreat();
    }
    final IElementType tokenType = (IElementType) iterator.getTokenType();
    if (tokenType == JavaTokenType.DOT) {
      return true;
    }
    return isClassLikeIdentifier(offset, editor, iterator, JavaTokenType.IDENTIFIER);
  }

  public static boolean isClassLikeIdentifier(int offset,
                                              Editor editor,
                                              HighlighterIterator iterator,
                                              final IElementType idType) {
    if (iterator.getTokenType() == idType && iterator.getEnd() == offset) {
      final CharSequence chars = editor.getDocument().getCharsSequence();
      final char startChar = chars.charAt(iterator.getStart());
      if (!Character.isUpperCase(startChar)) {
        return false;
      }
      final CharSequence word = chars.subSequence(iterator.getStart(), iterator.getEnd());
      if (word.length() == 1) {
        return true;
      }
      for (int i = 1; i < word.length(); i++) {
        if (Character.isLowerCase(word.charAt(i))) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean autoIndentCase(Editor editor, Project project, PsiFile file) {
    int offset = editor.getCaretModel().getOffset();
    PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    PsiElement currElement = file.findElementAt(offset - 1);
    if (currElement != null) {
      PsiElement parent = currElement.getParent();
      if (parent != null && parent instanceof PsiSwitchLabelStatement) {
        CodeStyleManager.getInstance(project).adjustLineIndent(file, parent.getTextOffset());
        return true;
      }
    }
    return false;
  }
}
