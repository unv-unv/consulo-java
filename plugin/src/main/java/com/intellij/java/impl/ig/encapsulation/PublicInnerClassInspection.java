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
package com.intellij.java.impl.ig.encapsulation;

import com.intellij.java.impl.ig.fixes.MoveClassFix;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiModifier;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.component.ExtensionImpl;
import consulo.deadCodeNotWorking.impl.MultipleCheckboxOptionsPanel;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;

@ExtensionImpl
public class PublicInnerClassInspection extends BaseInspection {

  @SuppressWarnings({"PublicField"})
  public boolean ignoreEnums = false;

  @SuppressWarnings("PublicField")
  public boolean ignoreInterfaces = false;

  @Override
  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.publicInnerClassDisplayName().get();
  }

  @Override
  @Nonnull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsLocalize.publicInnerClassProblemDescriptor().get();
  }

  @Override
  @Nullable
  public JComponent createOptionsPanel() {
    final MultipleCheckboxOptionsPanel panel = new MultipleCheckboxOptionsPanel(this);
    panel.addCheckbox(InspectionGadgetsLocalize.publicInnerClassIgnoreEnumOption().get(), "ignoreEnums");
    panel.addCheckbox(InspectionGadgetsLocalize.publicInnerClassIgnoreInterfaceOption().get(), "ignoreInterfaces");
    return panel;
  }

  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new MoveClassFix();
  }

  @Override
  protected boolean buildQuickFixesOnlyForOnTheFlyErrors() {
    return true;
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new PublicInnerClassVisitor();
  }

  private class PublicInnerClassVisitor extends BaseInspectionVisitor {

    @Override
    public void visitClass(@Nonnull PsiClass aClass) {
      if (!aClass.hasModifierProperty(PsiModifier.PUBLIC)) {
        return;
      }
      if (!ClassUtils.isInnerClass(aClass)) {
        return;
      }
      if (ignoreEnums && aClass.isEnum()) {
        return;
      }
      if (ignoreInterfaces && aClass.isInterface()) {
        return;
      }
      registerClassError(aClass);
    }
  }
}