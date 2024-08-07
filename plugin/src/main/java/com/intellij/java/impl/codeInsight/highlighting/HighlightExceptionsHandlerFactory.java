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
package com.intellij.java.impl.codeInsight.highlighting;

import consulo.annotation.component.ExtensionImpl;
import consulo.externalService.statistic.FeatureUsageTracker;
import consulo.language.editor.highlight.usage.HighlightUsagesHandlerBase;
import consulo.language.editor.highlight.usage.HighlightUsagesHandlerFactory;
import com.intellij.java.language.impl.codeInsight.ExceptionUtil;
import com.intellij.java.language.psi.*;
import consulo.codeEditor.Editor;
import consulo.util.lang.function.Condition;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.editor.TargetElementUtil;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * @author yole
 */
@ExtensionImpl
public class HighlightExceptionsHandlerFactory implements HighlightUsagesHandlerFactory {
  @Override
  public HighlightUsagesHandlerBase createHighlightUsagesHandler(final Editor editor, final PsiFile file) {
    int offset = TargetElementUtil.adjustOffset(file, editor.getDocument(), editor.getCaretModel().getOffset());
    PsiElement target = file.findElementAt(offset);
    if (target instanceof PsiKeyword) {
      PsiElement parent = target.getParent();
      if (PsiKeyword.TRY.equals(target.getText()) && parent instanceof PsiTryStatement) {
        return createHighlightTryHandler(editor, file, target, parent);
      }
      if (PsiKeyword.CATCH.equals(target.getText()) && parent instanceof PsiCatchSection) {
        return createHighlightCatchHandler(editor, file, target, parent);
      }
      if (PsiKeyword.THROWS.equals(target.getText())) {
        return createThrowsHandler(editor, file, target);
      }
    }
    return null;
  }

  @Nullable
  private static HighlightUsagesHandlerBase createHighlightTryHandler(final Editor editor,
                                                                      final PsiFile file,
                                                                      final PsiElement target,
                                                                      final PsiElement parent) {
    final PsiTryStatement tryStatement = (PsiTryStatement) parent;
    FeatureUsageTracker.getInstance().triggerFeatureUsed("codeassists.highlight.throws");
    final PsiCodeBlock tryBlock = tryStatement.getTryBlock();
    if (tryBlock == null) return null;
    final Collection<PsiClassType> psiClassTypes = ExceptionUtil.collectUnhandledExceptions(tryBlock, tryBlock);
    return new HighlightExceptionsHandler(editor, file, target, psiClassTypes.toArray(new PsiClassType[psiClassTypes.size()]), tryBlock, Condition.TRUE);
  }

  @Nullable
  private static HighlightUsagesHandlerBase createHighlightCatchHandler(final Editor editor,
                                                                        final PsiFile file,
                                                                        final PsiElement target,
                                                                        final PsiElement parent) {
    final PsiCatchSection catchSection = (PsiCatchSection) parent;
    FeatureUsageTracker.getInstance().triggerFeatureUsed("codeassists.highlight.throws");

    PsiTryStatement tryStatement = catchSection.getTryStatement();

    final PsiParameter param = catchSection.getParameter();
    if (param == null) return null;

    final PsiParameter[] catchBlockParameters = tryStatement.getCatchBlockParameters();

    final Collection<PsiClassType> allThrownExceptions = ExceptionUtil.collectUnhandledExceptions(tryStatement.getTryBlock(),
        tryStatement.getTryBlock());
    Condition<PsiType> filter = new Condition<PsiType>() {
      @Override
      public boolean value(PsiType type) {
        for (PsiParameter parameter : catchBlockParameters) {
          boolean isAssignable = parameter.getType().isAssignableFrom(type);
          if (parameter != param) {
            if (isAssignable) return false;
          } else {
            return isAssignable;
          }
        }
        return false;
      }
    };

    ArrayList<PsiClassType> filtered = new ArrayList<PsiClassType>();
    for (PsiClassType type : allThrownExceptions) {
      if (filter.value(type)) filtered.add(type);
    }

    return new HighlightExceptionsHandler(editor, file, target, filtered.toArray(new PsiClassType[filtered.size()]),
        tryStatement.getTryBlock(), filter);
  }

  @Nullable
  private static HighlightUsagesHandlerBase createThrowsHandler(final Editor editor, final PsiFile file, final PsiElement target) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed("codeassists.highlight.throws");
    PsiElement grand = target.getParent().getParent();
    if (!(grand instanceof PsiMethod)) return null;
    PsiMethod method = (PsiMethod) grand;
    if (method.getBody() == null) return null;

    final Collection<PsiClassType> psiClassTypes = ExceptionUtil.collectUnhandledExceptions(method.getBody(), method.getBody());

    return new HighlightExceptionsHandler(editor, file, target, psiClassTypes.toArray(new PsiClassType[psiClassTypes.size()]), method.getBody(), Condition.TRUE);
  }
}
