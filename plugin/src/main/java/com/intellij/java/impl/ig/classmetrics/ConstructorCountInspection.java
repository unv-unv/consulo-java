/*
 * Copyright 2003-2013 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.classmetrics;

import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiMethod;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.deadCodeNotWorking.impl.CheckBox;
import consulo.ui.ex.awt.GridBag;
import consulo.ui.ex.awt.UIUtil;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;

public abstract class ConstructorCountInspection extends ClassMetricInspection {

  private static final int CONSTRUCTOR_COUNT_LIMIT = 5;

  public boolean ignoreDeprecatedConstructors = false;

  @Nonnull
  public String getID() {
    return "ClassWithTooManyConstructors";
  }

  @Nonnull
  public String getDisplayName() {
    return InspectionGadgetsLocalize.tooManyConstructorsDisplayName().get();
  }

  protected int getDefaultLimit() {
    return CONSTRUCTOR_COUNT_LIMIT;
  }

  protected String getConfigurationLabel() {
    return InspectionGadgetsLocalize.tooManyConstructorsCountLimitOption().get();
  }

  @Override
  public JComponent createOptionsPanel() {
    final JLabel label = new JLabel(getConfigurationLabel());
    final JFormattedTextField valueField = prepareNumberEditor(() -> m_limit, i -> m_limit = i);
    final CheckBox includeCheckBox = new CheckBox(
      InspectionGadgetsLocalize.tooManyConstructorsIgnoreDeprecatedOption().get(),
      this,
      "ignoreDeprecatedConstructors"
    );

    final GridBag bag = new GridBag();
    bag.setDefaultInsets(0, 0, 0, UIUtil.DEFAULT_HGAP);
    bag.setDefaultAnchor(GridBagConstraints.WEST);
    final JPanel panel = new JPanel(new GridBagLayout());
    panel.add(label, bag.nextLine().next());
    panel.add(valueField, bag.next().weightx(1.0));
    panel.add(includeCheckBox, bag.nextLine().next().coverLine().weighty(1.0).anchor(GridBagConstraints.NORTHWEST));
    return panel;
  }

  @Nonnull
  public String buildErrorString(Object... infos) {
    final Integer count = (Integer)infos[0];
    return InspectionGadgetsLocalize.tooManyConstructorsProblemDescriptor(count).get();
  }

  public BaseInspectionVisitor buildVisitor() {
    return new ConstructorCountVisitor();
  }

  private class ConstructorCountVisitor extends BaseInspectionVisitor {

    @Override
    public void visitClass(@Nonnull PsiClass aClass) {
      final int constructorCount = calculateTotalConstructorCount(aClass);
      if (constructorCount <= getLimit()) {
        return;
      }
      registerClassError(aClass, Integer.valueOf(constructorCount));
    }

    private int calculateTotalConstructorCount(PsiClass aClass) {
      final PsiMethod[] constructors = aClass.getConstructors();
      if (!ignoreDeprecatedConstructors) {
        return constructors.length;
      }
      int count = 0;
      for (PsiMethod constructor : constructors) {
        if (!constructor.isDeprecated()) {
          count++;
        }
      }
      return count;
    }
  }
}