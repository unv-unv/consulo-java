/*
 * Copyright 2003-2011 Dave Griffith, Bas Leijdekkers
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
import com.intellij.java.language.psi.util.PsiUtil;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.deadCodeNotWorking.impl.SingleCheckboxOptionsPanel;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.NonNls;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

@ExtensionImpl
public class StandardVariableNamesInspection extends BaseInspection {

  @NonNls static final Map<String, String> s_expectedTypes =
    new HashMap<String, String>(13);
  @NonNls static final Map<String, String> s_boxingClasses =
    new HashMap<String, String>(8);

  static {
    s_expectedTypes.put("b", "byte");
    s_expectedTypes.put("c", "char");
    s_expectedTypes.put("ch", "char");
    s_expectedTypes.put("d", "double");
    s_expectedTypes.put("f", "float");
    s_expectedTypes.put("i", "int");
    s_expectedTypes.put("j", "int");
    s_expectedTypes.put("k", "int");
    s_expectedTypes.put("m", "int");
    s_expectedTypes.put("n", "int");
    s_expectedTypes.put("l", "long");
    s_expectedTypes.put("s", CommonClassNames.JAVA_LANG_STRING);
    s_expectedTypes.put("str", CommonClassNames.JAVA_LANG_STRING);

    s_boxingClasses.put("int", CommonClassNames.JAVA_LANG_INTEGER);
    s_boxingClasses.put("short", CommonClassNames.JAVA_LANG_SHORT);
    s_boxingClasses.put("boolean", CommonClassNames.JAVA_LANG_BOOLEAN);
    s_boxingClasses.put("long", CommonClassNames.JAVA_LANG_LONG);
    s_boxingClasses.put("byte", CommonClassNames.JAVA_LANG_BYTE);
    s_boxingClasses.put("float", CommonClassNames.JAVA_LANG_FLOAT);
    s_boxingClasses.put("double", CommonClassNames.JAVA_LANG_DOUBLE);
    s_boxingClasses.put("char", CommonClassNames.JAVA_LANG_CHARACTER);
  }

  @SuppressWarnings("PublicField")
  public boolean ignoreParameterNameSameAsSuper = false;

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.standardVariableNamesDisplayName().get();
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
  @Nonnull
  public String buildErrorString(Object... infos) {
    final PsiVariable variable = (PsiVariable)infos[0];
    final String name = variable.getName();
    final String expectedType = s_expectedTypes.get(name);
    if (PsiUtil.isLanguageLevel5OrHigher(variable)) {
      final String boxedType = s_boxingClasses.get(expectedType);
      if (boxedType != null) {
        return InspectionGadgetsLocalize.standardVariableNamesProblemDescriptor2(expectedType, boxedType).get();
      }
    }
    return InspectionGadgetsLocalize.standardVariableNamesProblemDescriptor(expectedType).get();
  }

  @Override
  public JComponent createOptionsPanel() {
    LocalizeValue message = InspectionGadgetsLocalize.standardVariableNamesIgnoreOverrideOption();
    return new SingleCheckboxOptionsPanel(message.get(), this, "ignoreParameterNameSameAsSuper");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new StandardVariableNamesVisitor();
  }

  private class StandardVariableNamesVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitVariable(@Nonnull PsiVariable variable) {
      super.visitVariable(variable);
      final String variableName = variable.getName();
      final String expectedType = s_expectedTypes.get(variableName);
      if (expectedType == null) {
        return;
      }
      final PsiType type = variable.getType();
      final String typeText = type.getCanonicalText();
      if (expectedType.equals(typeText)) {
        return;
      }
      if (PsiUtil.isLanguageLevel5OrHigher(variable)) {
        final PsiPrimitiveType unboxedType =
          PsiPrimitiveType.getUnboxedType(type);
        if (unboxedType != null) {
          final String unboxedTypeText =
            unboxedType.getCanonicalText();
          if (expectedType.equals(unboxedTypeText)) {
            return;
          }
        }
      }
      if (ignoreParameterNameSameAsSuper &&
          isVariableNamedSameAsSuper(variable)) {
        return;
      }
      registerVariableError(variable, variable);
    }

    private boolean isVariableNamedSameAsSuper(PsiVariable variable) {
      if (!(variable instanceof PsiParameter)) {
        return false;
      }
      final PsiParameter parameter = (PsiParameter)variable;
      final PsiElement scope = parameter.getDeclarationScope();
      if (!(scope instanceof PsiMethod)) {
        return false;
      }
      final String variableName = variable.getName();
      final PsiMethod method = (PsiMethod)scope;
      final int index =
        method.getParameterList().getParameterIndex(parameter);
      final PsiMethod[] superMethods = method.findSuperMethods();
      for (PsiMethod superMethod : superMethods) {
        final PsiParameter[] parameters =
          superMethod.getParameterList().getParameters();
        final PsiParameter overriddenParameter = parameters[index];
        if (variableName.equals(overriddenParameter.getName())) {
          return true;
        }
      }
      return false;
    }
  }
}