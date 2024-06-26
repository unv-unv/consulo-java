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
package com.intellij.java.impl.codeInsight.unwrap;

import consulo.language.editor.refactoring.unwrap.AbstractUnwrapper;
import com.intellij.java.language.psi.*;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiWhiteSpace;
import consulo.language.util.IncorrectOperationException;

public abstract class JavaUnwrapper extends AbstractUnwrapper<JavaUnwrapper.Context> {

  public JavaUnwrapper(String description) {
    super(description);
  }

  @Override
  protected Context createContext() {
    return new Context();
  }

  protected static class Context extends AbstractUnwrapper.AbstractContext {

    public void extractFromBlockOrSingleStatement(PsiStatement block, PsiElement from) throws IncorrectOperationException {
      if (block instanceof PsiBlockStatement) {
        extractFromCodeBlock(((PsiBlockStatement) block).getCodeBlock(), from);
      } else if (block != null && !(block instanceof PsiEmptyStatement)) {
        extract(block, block, from);
      }
    }

    public void extractFromCodeBlock(PsiCodeBlock block, PsiElement from) throws IncorrectOperationException {
      if (block == null) return;
      extract(block.getFirstBodyElement(), block.getLastBodyElement(), from);
    }

    public void setElseBranch(PsiIfStatement ifStatement, PsiStatement elseBranch) throws IncorrectOperationException {
      PsiStatement toExtract = elseBranch;
      if (myIsEffective) {
        ifStatement.setElseBranch(copyElement(elseBranch));
        toExtract = ifStatement.getElseBranch();
      }
      addElementToExtract(toExtract);
    }

    @Override
    protected boolean isWhiteSpace(PsiElement element) {
      return element instanceof PsiWhiteSpace;
    }

    private static PsiStatement copyElement(PsiStatement e) throws IncorrectOperationException {
      // We cannot call el.copy() for 'else' since it sets context to parent 'if'.
      // This causes copy to be invalidated after parent 'if' is removed by setElseBranch method.
      PsiElementFactory factory = JavaPsiFacade.getInstance(e.getProject()).getElementFactory();
      return factory.createStatementFromText(e.getText(), null);
    }
  }
}
