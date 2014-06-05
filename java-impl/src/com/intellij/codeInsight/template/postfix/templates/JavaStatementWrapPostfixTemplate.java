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
package com.intellij.codeInsight.template.postfix.templates;


import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;

public abstract class JavaStatementWrapPostfixTemplate extends StatementWrapPostfixTemplate {

  protected JavaStatementWrapPostfixTemplate(@NotNull String name,
                                             @NotNull String descr,
                                             @NotNull PostfixTemplatePsiInfo psiInfo,
                                             @NotNull Condition<PsiElement> typeChecker) {
    super(name, descr, psiInfo, typeChecker);
  }

  @Override
  protected void afterExpand(@NotNull PsiElement newElement, @NotNull Editor editor) {
    super.afterExpand(newElement, editor);
    JavaPostfixTemplateProvider.doNotDeleteSemicolon(newElement.getContainingFile());
  }

  @NotNull
  @Override
  protected String getTail() {
    return ";";
  }
}