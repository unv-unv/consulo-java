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
package com.intellij.java.impl.ig.inheritance;

import com.intellij.java.language.psi.*;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.TestUtils;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.deadCodeNotWorking.impl.SingleCheckboxOptionsPanel;
import consulo.language.psi.PsiElement;
import consulo.util.lang.xml.XmlStringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;

@ExtensionImpl
public class RefusedBequestInspection extends BaseInspection {

  @SuppressWarnings("PublicField") public boolean ignoreEmptySuperMethods = false;

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.refusedBequestDisplayName().get();
  }

  @Override
  @Nonnull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.refusedBequestProblemDescriptor().get();
  }

  @Override
  public JComponent createOptionsPanel() {
    //noinspection HardCodedStringLiteral
    return new SingleCheckboxOptionsPanel(
      XmlStringUtil.wrapInHtml(InspectionGadgetsLocalize.refusedBequestIgnoreEmptySuperMethodsOption().get()),
      this,
      "ignoreEmptySuperMethods"
    );
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new RefusedBequestVisitor();
  }

  private class RefusedBequestVisitor extends BaseInspectionVisitor {

    @Override
    public void visitMethod(@Nonnull PsiMethod method) {
      super.visitMethod(method);
      final PsiCodeBlock body = method.getBody();
      if (body == null) {
        return;
      }
      if (method.getNameIdentifier() == null) {
        return;
      }
      final PsiMethod leastConcreteSuperMethod = getLeastConcreteSuperMethod(method);
      if (leastConcreteSuperMethod == null) {
        return;
      }
      final PsiClass objectClass = ClassUtils.findObjectClass(method);
      final PsiMethod[] superMethods = method.findSuperMethods(objectClass);
      if (superMethods.length > 0) {
        return;
      }
      if (ignoreEmptySuperMethods) {
        final PsiMethod superMethod = (PsiMethod)leastConcreteSuperMethod.getNavigationElement();
        if (isTrivial(superMethod)) {
          return;
        }
      }
      if (TestUtils.isJUnit4BeforeOrAfterMethod(method)) {
        return;
      }
      if (containsSuperCall(body, leastConcreteSuperMethod)) {
        return;
      }
      registerMethodError(method);
    }

    private boolean isTrivial(PsiMethod method) {
      final PsiCodeBlock body = method.getBody();
      if (body == null) {
        return true;
      }
      final PsiStatement[] statements = body.getStatements();
      if (statements.length == 0) {
        return true;
      }
      if (statements.length > 1) {
        return false;
      }
      final PsiStatement statement = statements[0];
      if (statement instanceof PsiThrowStatement) {
        return true;
      }
      if (statement instanceof PsiReturnStatement) {
        final PsiReturnStatement returnStatement = (PsiReturnStatement)statement;
        final PsiExpression returnValue = returnStatement.getReturnValue();
        if (returnValue instanceof PsiLiteralExpression) {
          return true;
        }
      }
      return false;
    }

    @Nullable
    private PsiMethod getLeastConcreteSuperMethod(PsiMethod method) {
      final PsiMethod[] superMethods = method.findSuperMethods(true);
      for (final PsiMethod superMethod : superMethods) {
        final PsiClass containingClass = superMethod.getContainingClass();
        if (containingClass != null && !superMethod.hasModifierProperty(PsiModifier.ABSTRACT) && !containingClass.isInterface()) {
          return superMethod;
        }
      }
      return null;
    }

    private boolean containsSuperCall(@Nonnull PsiElement context, @Nonnull PsiMethod method) {
      final SuperCallVisitor visitor = new SuperCallVisitor(method);
      context.accept(visitor);
      return visitor.hasSuperCall();
    }
  }

  private static class SuperCallVisitor extends JavaRecursiveElementVisitor {

    private final PsiMethod methodToSearchFor;
    private boolean hasSuperCall = false;

    SuperCallVisitor(PsiMethod methodToSearchFor) {
      this.methodToSearchFor = methodToSearchFor;
    }

    @Override
    public void visitElement(@Nonnull PsiElement element) {
      if (hasSuperCall) {
        return;
      }
      super.visitElement(element);
    }

    @Override
    public void visitMethodCallExpression(
      @Nonnull PsiMethodCallExpression expression) {
      if (hasSuperCall) {
        return;
      }
      super.visitMethodCallExpression(expression);
      final PsiReferenceExpression methodExpression = expression.getMethodExpression();
      final PsiExpression qualifier = methodExpression.getQualifierExpression();
      if (qualifier == null) {
        return;
      }
      final String text = qualifier.getText();
      if (!PsiKeyword.SUPER.equals(text)) {
        return;
      }
      final PsiMethod method = expression.resolveMethod();
      if (method == null) {
        return;
      }
      if (method.equals(methodToSearchFor)) {
        hasSuperCall = true;
      }
    }

    public boolean hasSuperCall() {
      return hasSuperCall;
    }
  }
}
