/*
 * Copyright 2003-2010 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.naming;

import com.intellij.java.impl.ig.fixes.RenameFix;
import com.intellij.java.language.psi.*;
import com.siyeh.HardcodedMethodConstants;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.psiutils.TypeUtils;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import jakarta.annotation.Nonnull;

@ExtensionImpl
public class ConfusingMainMethodInspection extends BaseInspection {

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.confusingMainMethodDisplayName().get();
  }

  @Override
  @Nonnull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.confusingMainMethodProblemDescriptor().get();
  }

  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new RenameFix();
  }

  @Override
  protected boolean buildQuickFixesOnlyForOnTheFlyErrors() {
    return true;
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new ConfusingMainMethodVisitor();
  }

  private static class ConfusingMainMethodVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitMethod(@Nonnull PsiMethod aMethod) {
      // no call to super, so it doesn't drill down into inner classes
      final String methodName = aMethod.getName();
      if (!HardcodedMethodConstants.MAIN.equals(methodName)) {
        return;
      }
      if (!aMethod.hasModifierProperty(PsiModifier.PUBLIC)) {
        registerMethodError(aMethod);
        return;
      }
      if (!aMethod.hasModifierProperty(PsiModifier.STATIC)) {
        registerMethodError(aMethod);
        return;
      }
      final PsiType returnType = aMethod.getReturnType();

      if (!TypeUtils.typeEquals(PsiKeyword.VOID, returnType)) {
        registerMethodError(aMethod);
        return;
      }
      final PsiParameterList parameterList = aMethod.getParameterList();
      if (parameterList.getParametersCount() != 1) {
        registerMethodError(aMethod);
        return;
      }
      final PsiParameter[] parameters = parameterList.getParameters();
      final PsiType parameterType = parameters[0].getType();
      if (!TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING + "[]", parameterType)) {
        registerMethodError(aMethod);
      }
    }
  }
}