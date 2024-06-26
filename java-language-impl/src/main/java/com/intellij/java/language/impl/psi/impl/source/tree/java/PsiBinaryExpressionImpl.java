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
package com.intellij.java.language.impl.psi.impl.source.tree.java;

import com.intellij.java.language.impl.psi.impl.source.resolve.JavaResolveCache;
import com.intellij.java.language.impl.psi.impl.source.tree.ChildRole;
import com.intellij.java.language.impl.psi.impl.source.tree.ElementType;
import com.intellij.java.language.impl.psi.impl.source.tree.JavaElementType;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.language.ast.ASTNode;
import consulo.language.ast.ChildRoleBase;
import consulo.language.ast.IElementType;
import consulo.language.ast.TokenSet;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.psi.resolve.PsiScopeProcessor;
import consulo.language.psi.resolve.ResolveState;
import consulo.logging.Logger;

import jakarta.annotation.Nonnull;
import java.util.function.Function;

public class PsiBinaryExpressionImpl extends ExpressionPsiElement implements PsiBinaryExpression {
  private static final Logger LOG = Logger.getInstance(PsiBinaryExpressionImpl.class);

  /**
   * used via reflection in {@link JavaElementType.JavaCompositeElementType#JavaCompositeElementType(String, Class)}
   */
  @SuppressWarnings("UnusedDeclaration")
  public PsiBinaryExpressionImpl() {
    this(JavaElementType.BINARY_EXPRESSION);
  }

  protected PsiBinaryExpressionImpl(@Nonnull IElementType elementType) {
    super(elementType);
  }

  @Override
  @Nonnull
  public PsiExpression getLOperand() {
    return (PsiExpression) findChildByRoleAsPsiElement(ChildRole.LOPERAND);
  }

  @Override
  public PsiExpression getROperand() {
    return (PsiExpression) findChildByRoleAsPsiElement(ChildRole.ROPERAND);
  }

  @Override
  @Nonnull
  public PsiJavaToken getOperationSign() {
    return (PsiJavaToken) findChildByRoleAsPsiElement(ChildRole.OPERATION_SIGN);
  }

  @Override
  @Nonnull
  public IElementType getOperationTokenType() {
    return getOperationSign().getTokenType();
  }

  @Override
  public PsiJavaToken getTokenBeforeOperand(@Nonnull PsiExpression operand) {
    if (operand != getROperand()) {
      return null;
    }
    return getOperationSign();
  }

  private static PsiType doGetType(PsiBinaryExpressionImpl param) {
    PsiExpression lOperand = param.getLOperand();
    PsiExpression rOperand = param.getROperand();
    if (rOperand == null) {
      return null;
    }
    PsiType rType = rOperand.getType();
    IElementType sign = param.getOperationTokenType();
    // optimization: if we can calculate type based on right type only
    PsiType type = TypeConversionUtil.calcTypeForBinaryExpression(null, rType, sign, false);
    if (type != TypeConversionUtil.NULL_TYPE) {
      return type;
    }

    PsiType lType = lOperand.getType();
    return TypeConversionUtil.calcTypeForBinaryExpression(lType, rType, sign, true);
  }

  private static final Function<PsiBinaryExpressionImpl, PsiType> MY_TYPE_EVALUATOR = new Function<PsiBinaryExpressionImpl, PsiType>() {
    @Override
    public PsiType apply(PsiBinaryExpressionImpl expression) {
      return doGetType(expression);
    }
  };

  @Override
  public PsiType getType() {
    return JavaResolveCache.getInstance(getProject()).getType(this, MY_TYPE_EVALUATOR);
  }

  @Override
  public ASTNode findChildByRole(int role) {
    LOG.assertTrue(ChildRole.isUnique(role));
    switch (role) {
      default:
        return null;

      case ChildRole.LOPERAND:
        return getFirstChildNode();

      case ChildRole.ROPERAND:
        return ElementType.EXPRESSION_BIT_SET.contains(getLastChildNode().getElementType()) ? getLastChildNode() : null;

      case ChildRole.OPERATION_SIGN:
        return findChildByType(OUR_OPERATIONS_BIT_SET);
    }
  }

  @Override
  public int getChildRole(ASTNode child) {
    LOG.assertTrue(child.getTreeParent() == this);
    if (ElementType.EXPRESSION_BIT_SET.contains(child.getElementType())) {
      if (child == getFirstChildNode()) {
        return ChildRole.LOPERAND;
      }
      if (child == getLastChildNode()) {
        return ChildRole.ROPERAND;
      }
      return ChildRoleBase.NONE;
    }
    if (OUR_OPERATIONS_BIT_SET.contains(child.getElementType())) {
      return ChildRole.OPERATION_SIGN;
    }
    return ChildRoleBase.NONE;
  }

  private static final TokenSet OUR_OPERATIONS_BIT_SET =
      TokenSet.create(JavaTokenType.OROR, JavaTokenType.ANDAND, JavaTokenType.OR, JavaTokenType.XOR, JavaTokenType.AND, JavaTokenType.EQEQ,
          JavaTokenType.NE, JavaTokenType.LT, JavaTokenType.GT, JavaTokenType.LE, JavaTokenType.GE, JavaTokenType.LTLT,
          JavaTokenType.GTGT, JavaTokenType.GTGTGT, JavaTokenType.PLUS, JavaTokenType.MINUS, JavaTokenType.ASTERISK, JavaTokenType.DIV,
          JavaTokenType.PERC);

  @Override
  public void accept(@Nonnull PsiElementVisitor visitor) {
    if (visitor instanceof JavaElementVisitor) {
      ((JavaElementVisitor) visitor).visitBinaryExpression(this);
    } else {
      visitor.visitElement(this);
    }
  }

  public String toString() {
    return "PsiBinaryExpression:" + getText();
  }


  @Override
  public boolean processDeclarations(@Nonnull PsiScopeProcessor processor,
                                     @Nonnull ResolveState state,
                                     PsiElement lastParent,
                                     @Nonnull PsiElement place) {
    return PsiPolyadicExpressionImpl.processDeclarations(this, processor, state, lastParent, place);
  }

  @Nonnull
  @Override
  public PsiExpression[] getOperands() {
    PsiExpression rOperand = getROperand();
    return rOperand == null ? new PsiExpression[]{getLOperand()} : new PsiExpression[]{
        getLOperand(),
        rOperand
    };
  }
}

