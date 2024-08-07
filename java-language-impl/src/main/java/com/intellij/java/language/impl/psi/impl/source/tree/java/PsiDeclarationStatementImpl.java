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
package com.intellij.java.language.impl.psi.impl.source.tree.java;

import com.intellij.java.language.impl.psi.impl.source.tree.ChildRole;
import com.intellij.java.language.impl.psi.impl.source.tree.ElementType;
import com.intellij.java.language.impl.psi.impl.source.tree.JavaElementType;
import com.intellij.java.language.impl.psi.scope.ElementClassHint;
import com.intellij.java.language.psi.JavaElementVisitor;
import com.intellij.java.language.psi.JavaTokenType;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiDeclarationStatement;
import consulo.language.ast.ASTNode;
import consulo.language.ast.TokenSet;
import consulo.language.impl.ast.*;
import consulo.language.impl.psi.CompositePsiElement;
import consulo.language.impl.psi.SourceTreeToPsiMap;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.psi.resolve.PsiScopeProcessor;
import consulo.language.psi.resolve.ResolveState;

import jakarta.annotation.Nonnull;

public class PsiDeclarationStatementImpl extends CompositePsiElement implements PsiDeclarationStatement {
  public PsiDeclarationStatementImpl() {
    super(JavaElementType.DECLARATION_STATEMENT);
  }

  @Override
  @Nonnull
  public PsiElement[] getDeclaredElements() {
    return getChildrenAsPsiElements(DECLARED_ELEMENT_BIT_SET, PsiElement.ARRAY_FACTORY);
  }

  private static final TokenSet DECLARED_ELEMENT_BIT_SET = TokenSet.create(JavaElementType.LOCAL_VARIABLE, JavaElementType.CLASS);

  @Override
  public int getChildRole(ASTNode child) {
    if (child.getElementType() == JavaTokenType.COMMA) return ChildRole.COMMA;
    return super.getChildRole(child);
  }

  @Override
  public void deleteChildInternal(@Nonnull ASTNode child) {
    if (DECLARED_ELEMENT_BIT_SET.contains(child.getElementType())) {
      PsiElement[] declaredElements = getDeclaredElements();
      int length = declaredElements.length;
      if (length > 0) {
        if (length == 1) {
          getTreeParent().deleteChildInternal(this);
          return;
        } else {
          if (SourceTreeToPsiMap.psiElementToTree(declaredElements[length - 1]) == child) {
            removeCommaBefore(child);
            final LeafElement semicolon = Factory.createSingleLeafElement(JavaTokenType.SEMICOLON, ";", 0, 1,
                                                                          SharedImplUtil.findCharTableByTree(this), getManager());
            SourceTreeToPsiMap.psiElementToTree(declaredElements[length - 2]).addChild(semicolon, null);
          }
          else if (SourceTreeToPsiMap.psiElementToTree(declaredElements[0]) == child) {
            CompositeElement next = (CompositeElement)SourceTreeToPsiMap.psiElementToTree(declaredElements[1]);
            ASTNode copyChild = child.copyElement();
            ASTNode nameChild = ((CompositeElement)copyChild).findChildByRole(ChildRole.NAME);
            removeCommaBefore(next);
            next.addInternal((TreeElement)copyChild.getFirstChildNode(), nameChild.getTreePrev(), null, Boolean.FALSE);
          }
          else {
            removeCommaBefore (child);
          }
        }
      }
    }
    super.deleteChildInternal(child);
  }

  private void removeCommaBefore(ASTNode child) {
    ASTNode prev = child;
    do {
      prev = prev.getTreePrev();
    } while (prev != null && ElementType.JAVA_COMMENT_OR_WHITESPACE_BIT_SET.contains(prev.getElementType()));
    if (prev != null && prev.getElementType() == JavaTokenType.COMMA) deleteChildInternal(prev);
  }

  @Override
  public void accept(@Nonnull PsiElementVisitor visitor) {
    if (visitor instanceof JavaElementVisitor) {
      ((JavaElementVisitor)visitor).visitDeclarationStatement(this);
    }
    else {
      visitor.visitElement(this);
    }
  }

  public String toString() {
    return "PsiDeclarationStatement";
  }

  @Override
  public boolean processDeclarations(@Nonnull PsiScopeProcessor processor, @Nonnull ResolveState state, PsiElement lastParent, @Nonnull PsiElement place) {
    processor.handleEvent(PsiScopeProcessor.Event.SET_DECLARATION_HOLDER, this);
    PsiElement[] decls = getDeclaredElements();
    for (PsiElement decl : decls) {
      if (decl != lastParent) {
        if (!processor.execute(decl, state)) return false;
      }
      else {
        final ElementClassHint hint = processor.getHint(ElementClassHint.KEY);
        if (lastParent instanceof PsiClass) {
          if (hint == null || hint.shouldProcess(ElementClassHint.DeclarationKind.CLASS)) {
            if (!processor.execute(lastParent, state)) return false;
          }
        }
      }
    }

    return true;
  }
}
