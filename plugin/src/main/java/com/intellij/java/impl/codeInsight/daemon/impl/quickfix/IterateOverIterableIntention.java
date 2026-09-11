/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.InheritanceUtil;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.codeEditor.SelectionModel;
import consulo.document.util.TextRange;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.editor.intention.IntentionMetaData;
import consulo.language.editor.template.Template;
import consulo.language.editor.template.TemplateManager;
import consulo.language.editor.template.TemplateSettings;
import consulo.language.editor.template.context.TemplateActionContext;
import consulo.language.psi.PsiComment;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiWhiteSpace;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * @author anna
 */
@ExtensionImpl
@IntentionMetaData(ignoreId = "java.IterateOverIterableIntention", categories = {"Java", "Control Flow"}, fileExtensions = "java")
public class IterateOverIterableIntention implements IntentionAction {
    @Override
    @RequiredReadAction
    public boolean isAvailable(Project project, Editor editor, PsiFile file) {
        Template template = getTemplate();
        if (template != null) {
            int offset = editor.getCaretModel().getOffset();
            int startOffset = offset;
            if (editor.getSelectionModel().hasSelection()) {
                int selStart = editor.getSelectionModel().getSelectionStart();
                int selEnd = editor.getSelectionModel().getSelectionEnd();
                startOffset = (offset == selStart) ? selEnd : selStart;
            }
            PsiElement element = file.findElementAt(startOffset);
            while (element instanceof PsiWhiteSpace whiteSpace) {
                element = whiteSpace.getPrevSibling();
            }
            PsiStatement psiStatement = PsiTreeUtil.getParentOfType(element, PsiStatement.class, false);
            if (psiStatement != null) {
                startOffset = psiStatement.getTextRange().getStartOffset();
            }
            TemplateManager templateManager = TemplateManager.getInstance(project);
            if (!template.isDeactivated() &&
                (templateManager.isApplicable(template, TemplateActionContext.expanding(file, offset)) ||
                    templateManager.isApplicable(template, TemplateActionContext.expanding(file, startOffset)))) {
                return getIterableExpression(editor, file) != null;
            }
        }
        return false;
    }

    private static @Nullable Template getTemplate() {
        return TemplateSettings.getInstance().getTemplate("I", "surround");
    }

    @Override
    public LocalizeValue getText() {
        return LocalizeValue.localizeTODO("Iterate");
    }

    @RequiredReadAction
    private static @Nullable PsiExpression getIterableExpression(Editor editor, PsiFile file) {
        SelectionModel selectionModel = editor.getSelectionModel();
        if (selectionModel.hasSelection()) {
            PsiElement elementAtStart = file.findElementAt(selectionModel.getSelectionStart());
            PsiElement elementAtEnd = file.findElementAt(selectionModel.getSelectionEnd() - 1);
            if (elementAtStart == null || elementAtStart instanceof PsiWhiteSpace || elementAtStart instanceof PsiComment) {
                elementAtStart = PsiTreeUtil.skipSiblingsForward(elementAtStart, PsiWhiteSpace.class, PsiComment.class);
                if (elementAtStart == null) {
                    return null;
                }
            }
            if (elementAtEnd == null || elementAtEnd instanceof PsiWhiteSpace || elementAtEnd instanceof PsiComment) {
                elementAtEnd = PsiTreeUtil.skipSiblingsBackward(elementAtEnd, PsiWhiteSpace.class, PsiComment.class);
                if (elementAtEnd == null) {
                    return null;
                }
            }
            PsiElement parent = PsiTreeUtil.findCommonParent(elementAtStart, elementAtEnd);
            if (parent instanceof PsiExpression parentExpr) {
                return isIterableType(parentExpr.getType()) ? parentExpr : null;
            }
            return null;
        }

        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        while (element instanceof PsiWhiteSpace whiteSpace) {
            element = whiteSpace.getPrevSibling();
        }
        if (element instanceof PsiExpressionStatement exprStmt) {
            element = exprStmt.getExpression().getLastChild();
        }

        while ((element = PsiTreeUtil.getParentOfType(element, PsiExpression.class, true)) != null) {
            PsiElement parent = element.getParent();
            if (parent instanceof PsiMethodCallExpression) {
                continue;
            }
            if (!(parent instanceof PsiExpressionStatement)) {
                return null;
            }
            PsiExpression expr = (PsiExpression) element;
            if (isIterableType(expr.getType())) {
                return expr;
            }
        }
        return null;
    }

    private static boolean isIterableType(@Nullable PsiType type) {
        return type instanceof PsiArrayType || InheritanceUtil.isInheritor(type, CommonClassNames.JAVA_LANG_ITERABLE);
    }

    @Override
    @RequiredUIAccess
    public void invoke(Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        Template template = getTemplate();
        if (template == null) {
            return;
        }

        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            PsiExpression iterableExpression = Objects.requireNonNull(getIterableExpression(editor, file));
            TextRange textRange = iterableExpression.getTextRange();
            selectionModel.setSelection(textRange.getStartOffset(), textRange.getEndOffset());
        }
        TemplateManager.getInstance(project).startTemplateForAllCarets(editor, template);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
