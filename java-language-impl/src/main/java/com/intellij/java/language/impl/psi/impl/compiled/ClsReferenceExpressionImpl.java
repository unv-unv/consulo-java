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
package com.intellij.java.language.impl.psi.impl.compiled;

import com.intellij.java.language.impl.psi.impl.source.tree.JavaElementType;
import com.intellij.java.language.psi.*;
import consulo.document.util.TextRange;
import consulo.language.impl.ast.TreeElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.psi.resolve.PsiScopeProcessor;
import consulo.language.util.IncorrectOperationException;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class ClsReferenceExpressionImpl extends ClsElementImpl implements PsiReferenceExpression {
  private final ClsElementImpl myParent;
  private final PsiReferenceExpression myPatternExpression;
  private final PsiReferenceExpression myQualifier;
  private final String myName;
  private final PsiIdentifier myNameElement;

  public ClsReferenceExpressionImpl(ClsElementImpl parent, PsiReferenceExpression patternExpression) {
    myParent = parent;
    myPatternExpression = patternExpression;

    PsiReferenceExpression patternQualifier = (PsiReferenceExpression)myPatternExpression.getQualifierExpression();
    if (patternQualifier != null) {
      myQualifier = new ClsReferenceExpressionImpl(this, patternQualifier);
    }
    else {
      myQualifier = null;
    }

    myName = myPatternExpression.getReferenceName();
    myNameElement = new ClsIdentifierImpl(this, myName);
  }

  @Override
  public PsiElement getParent() {
    return myParent;
  }

  @Override
  public PsiExpression getQualifierExpression() {
    return myQualifier;
  }

  @Override
  public PsiElement bindToElementViaStaticImport(@Nonnull PsiClass aClass) throws IncorrectOperationException {
    throw new IncorrectOperationException();
  }

  @Override
  public void setQualifierExpression(@Nullable PsiExpression newQualifier) throws IncorrectOperationException {
    throw new IncorrectOperationException("This method should not be called for compiled elements");
  }

  @Override
  public PsiElement getReferenceNameElement() {
    return myNameElement;
  }

  @Override
  public PsiReferenceParameterList getParameterList() {
    return null;
  }

  @Override
  @Nonnull
  public PsiElement[] getChildren() {
    if (myQualifier != null) {
      return new PsiElement[]{myQualifier, myNameElement};
    }
    else {
      return new PsiElement[]{myNameElement};
    }
  }

  @Override
  public String getText() {
    return myQualifier != null ? myQualifier.getText() + "." + myName : myName;
  }

  @Override
  public boolean isQualified() {
    return myQualifier != null;
  }

  @Override
  public PsiType getType() {
    return myPatternExpression.getType();
  }

  @Override
  public PsiElement resolve() {
    return myPatternExpression.resolve();
  }

  @Override
  @Nonnull
  public JavaResolveResult advancedResolve(boolean incompleteCode) {
    return myPatternExpression.advancedResolve(incompleteCode);
  }

  @Override
  @Nonnull
  public JavaResolveResult[] multiResolve(boolean incompleteCode) {
    final JavaResolveResult result = advancedResolve(incompleteCode);
    return result != JavaResolveResult.EMPTY ? new JavaResolveResult[]{result} : JavaResolveResult.EMPTY_ARRAY;
  }

  @Override
  public PsiElement getElement() {
    return this;
  }

  @Override
  public TextRange getRangeInElement() {
    return new TextRange(0, getTextLength());
  }

  @Override
  @Nonnull
  public String getCanonicalText() {
    return myPatternExpression.getCanonicalText();
  }

  @Override
  public String getQualifiedName() {
    return getCanonicalText();
  }

  @Override
  public String getReferenceName() {
    return myPatternExpression.getReferenceName();
  }

  @Override
  public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
    throw new IncorrectOperationException();
  }

  @Override
  public PsiElement bindToElement(@Nonnull PsiElement element) throws IncorrectOperationException {
    throw new IncorrectOperationException();
  }

  @Override
  public boolean isReferenceTo(PsiElement element) {
    return myPatternExpression.isReferenceTo(element);
  }

  @Override
  @Nonnull
  public Object[] getVariants() {
    return myPatternExpression.getVariants();
  }

  @Override
  public void processVariants(PsiScopeProcessor processor) {
    myPatternExpression.processVariants(processor);
  }

  @Override
  public boolean isSoft() {
    return false;
  }

  @Override
  public void appendMirrorText(int indentLevel, @Nonnull StringBuilder buffer) {
    buffer.append(getText());
  }

  @Override
  public void setMirror(@Nonnull TreeElement element) throws InvalidMirrorException {
    setMirrorCheckingType(element, JavaElementType.REFERENCE_EXPRESSION);
  }

  @Override
  public void accept(@Nonnull PsiElementVisitor visitor) {
    if (visitor instanceof JavaElementVisitor) {
      ((JavaElementVisitor)visitor).visitReferenceExpression(this);
    }
    else {
      visitor.visitElement(this);
    }
  }

  @Override
  @Nonnull
  public PsiType[] getTypeParameters() {
    return PsiType.EMPTY_ARRAY;
  }

  @Override
  public PsiElement getQualifier() {
    return getQualifierExpression();
  }

  @Override
  public String toString() {
    return "PsiReferenceExpression:" + getText();
  }
}
