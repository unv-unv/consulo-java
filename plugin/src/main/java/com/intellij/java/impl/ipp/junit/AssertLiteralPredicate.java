/*
 * Copyright 2003-2009 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ipp.junit;

import com.intellij.java.language.psi.*;
import consulo.language.psi.*;
import com.intellij.java.impl.ipp.base.PsiElementPredicate;
import com.intellij.java.impl.ipp.psiutils.ErrorUtil;
import org.jetbrains.annotations.NonNls;

class AssertLiteralPredicate implements PsiElementPredicate {

  public boolean satisfiedBy(PsiElement element) {
    if (!(element instanceof PsiMethodCallExpression)) {
      return false;
    }
    final PsiMethodCallExpression expression =
      (PsiMethodCallExpression)element;
    final PsiExpressionList args = expression.getArgumentList();
    final int numExpressions = args.getExpressions().length;
    if (numExpressions < 1 || numExpressions > 2) {
      return false;
    }
    final PsiReferenceExpression methodExpression =
      expression.getMethodExpression();
    @NonNls final String methodName = methodExpression.getReferenceName();
    if (!("assertTrue".equals(methodName) ||
          "assertFalse".equals(methodName) ||
          "assertNull".equals(methodName))) {
      return false;
    }
    final PsiMethod method = expression.resolveMethod();
    if (method == null) {
      return false;
    }
    final PsiClass targetClass = method.getContainingClass();
    if (targetClass == null) {
      return false;
    }
    final String qualifiedName = targetClass.getQualifiedName();
    if (!"junit.framework.Assert".equals(qualifiedName) &&
        !"org.junit.Assert".equals(qualifiedName)) {
      return false;
    }
    return !ErrorUtil.containsError(element);
  }
}