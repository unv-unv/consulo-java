/*
 * Copyright 2003-2011 Bas Leijdekkers
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
package com.intellij.java.impl.ig.psiutils;

import com.intellij.java.language.psi.*;
import com.siyeh.HardcodedMethodConstants;
import com.siyeh.ig.psiutils.MethodCallUtils;
import consulo.language.psi.PsiElement;
import jakarta.annotation.Nonnull;

import java.util.regex.Pattern;

public class IteratorUtils {

  private IteratorUtils() {
  }

  /**
   * @param context the context iterator.next() may be called in.
   * @param target  the target variable iterator.next() is called on. Specify
   *                null to check for "this"  as target variable.
   * @return true, if iterator.next() is called, false otherwise
   */
  public static boolean containsCallToIteratorNext(PsiElement context,
                                                   PsiVariable target,
                                                   boolean checkTarget) {
    final CallsIteratorNextVisitor visitor =
        new CallsIteratorNextVisitor(target, checkTarget, false);
    context.accept(visitor);
    return visitor.callsIteratorNext();
  }

  public static boolean containsCallToScannerNext(PsiElement context,
                                                  PsiVariable target,
                                                  boolean checkTarget) {
    final CallsIteratorNextVisitor visitor =
        new CallsIteratorNextVisitor(target, checkTarget, true);
    context.accept(visitor);
    return visitor.callsIteratorNext();
  }

  public static boolean isCallToHasNext(
      PsiMethodCallExpression methodCallExpression) {
    return MethodCallUtils.isCallToMethod(methodCallExpression, CommonClassNames.JAVA_UTIL_ITERATOR, PsiType.BOOLEAN, "hasNext");
  }

  private static class CallsIteratorNextVisitor
      extends JavaRecursiveElementVisitor {

    private static final Pattern SCANNER_PATTERN = Pattern.compile("next.*");

    private final boolean checkTarget;
    private final boolean checkScanner;
    private final PsiVariable target;
    private boolean doesCallIteratorNext = false;

    CallsIteratorNextVisitor(PsiVariable target, boolean checkTarget,
                             boolean checkScanner) {
      this.checkTarget = checkTarget;
      this.target = target;
      this.checkScanner = checkScanner;
    }

    @Override
    public void visitElement(@Nonnull PsiElement element) {
      if (doesCallIteratorNext) {
        return;
      }
      super.visitElement(element);
    }

    @Override
    public void visitMethodCallExpression(
        @Nonnull PsiMethodCallExpression expression) {
      if (doesCallIteratorNext) {
        return;
      }
      super.visitMethodCallExpression(expression);
      if (checkScanner) {
        if (!MethodCallUtils.isCallToMethod(expression, CommonClassNames.JAVA_UTIL_ITERATOR, null, SCANNER_PATTERN)) {
          return;
        }
      } else if (!MethodCallUtils.isCallToMethod(expression, CommonClassNames.JAVA_UTIL_ITERATOR, null, HardcodedMethodConstants.NEXT)) {
        return;
      }
      if (checkTarget) {
        final PsiReferenceExpression methodExpression =
            expression.getMethodExpression();
        final PsiExpression qualifier =
            methodExpression.getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) {
          if (target != null) {
            return;
          }
          if (qualifier != null &&
              !(qualifier instanceof PsiThisExpression) &&
              !(qualifier instanceof PsiSuperExpression)) {
            return;
          }
        } else {
          final PsiReferenceExpression referenceExpression =
              (PsiReferenceExpression) qualifier;
          final PsiElement element = referenceExpression.resolve();
          if (target == null || !target.equals(element)) {
            return;
          }
        }
      }
      doesCallIteratorNext = true;
    }

    public boolean callsIteratorNext() {
      return doesCallIteratorNext;
    }
  }
}
