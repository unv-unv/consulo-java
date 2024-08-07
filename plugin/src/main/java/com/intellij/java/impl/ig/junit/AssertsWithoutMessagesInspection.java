/*
 * Copyright 2003-2012 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.junit;

import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.InheritanceUtil;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.TypeUtils;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.NonNls;

import java.util.HashSet;
import java.util.Set;

@ExtensionImpl
public class AssertsWithoutMessagesInspection extends BaseInspection {

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.assertsWithoutMessagesDisplayName().get();
  }

  @Override
  @Nonnull
  public String getID() {
    return "MessageMissingOnJUnitAssertion";
  }

  @Override
  @Nonnull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.assertsWithoutMessagesProblemDescriptor().get();
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new AssertionsWithoutMessagesVisitor();
  }

  private static class AssertionsWithoutMessagesVisitor extends BaseInspectionVisitor {

    @NonNls private static final Set<String> s_assertMethods = new HashSet<String>(8);

    static {
      s_assertMethods.add("assertArrayEquals");
      s_assertMethods.add("assertEquals");
      s_assertMethods.add("assertFalse");
      s_assertMethods.add("assertNotNull");
      s_assertMethods.add("assertNotSame");
      s_assertMethods.add("assertNull");
      s_assertMethods.add("assertSame");
      s_assertMethods.add("assertThat");
      s_assertMethods.add("assertTrue");
      s_assertMethods.add("fail");
    }

    @Override
    public void visitMethodCallExpression(@Nonnull PsiMethodCallExpression expression) {
      super.visitMethodCallExpression(expression);
      final PsiReferenceExpression methodExpression = expression.getMethodExpression();
      final String methodName = methodExpression.getReferenceName();
      if (methodName == null || !s_assertMethods.contains(methodName)) {
        return;
      }
      final PsiMethod method = expression.resolveMethod();
      if (method == null) {
        return;
      }
      final PsiClass containingClass = method.getContainingClass();
      if (!InheritanceUtil.isInheritor(containingClass, "junit.framework.Assert") &&
          !InheritanceUtil.isInheritor(containingClass, "org.junit.Assert")) {
        return;
      }
      final PsiParameterList parameterList = method.getParameterList();
      final int parameterCount = parameterList.getParametersCount();
      if (parameterCount < 2 && methodName.startsWith("assert")) {
        registerMethodCallError(expression);
        return;
      }
      if (parameterCount < 1) {
        registerMethodCallError(expression);
        return;
      }
      final PsiType stringType = TypeUtils.getStringType(expression);
      final PsiParameter[] parameters = parameterList.getParameters();
      final PsiType parameterType1 = parameters[0].getType();
      if (!parameterType1.equals(stringType)) {
        registerMethodCallError(expression);
        return;
      }
      if (parameters.length != 2) {
        return;
      }
      final PsiType parameterType2 = parameters[1].getType();
      if (!parameterType2.equals(stringType)) {
        return;
      }
      registerMethodCallError(expression);
    }
  }
}