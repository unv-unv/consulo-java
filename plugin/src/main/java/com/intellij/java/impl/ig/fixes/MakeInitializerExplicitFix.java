/*
 * Copyright 2003-2005 Dave Griffith
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
package com.intellij.java.impl.ig.fixes;

import com.intellij.java.language.psi.*;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiManager;
import consulo.language.util.IncorrectOperationException;
import consulo.project.Project;
import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.NonNls;

public class MakeInitializerExplicitFix extends InspectionGadgetsFix {

  @Nonnull
  public String getName() {
    return InspectionGadgetsLocalize.makeInitializationExplicitQuickfix().get();
  }

  public void doFix(Project project, ProblemDescriptor descriptor)
    throws IncorrectOperationException {
    final PsiElement fieldName = descriptor.getPsiElement();
    final PsiField field = (PsiField)fieldName.getParent();
    if (field == null) {
      return;
    }
    if (field.getInitializer() != null) {
      return;
    }
    final PsiType type = field.getType();
    final PsiManager psiManager = PsiManager.getInstance(project);
    final PsiElementFactory factory = JavaPsiFacade.getInstance(psiManager.getProject()).getElementFactory();
    final PsiExpression initializer =
      factory.createExpressionFromText(getDefaultValue(type), field);
    field.setInitializer(initializer);
  }

  @NonNls
  private static String getDefaultValue(PsiType type) {
    if (PsiType.INT.equals(type)) {
      return "0";
    }
    else if (PsiType.LONG.equals(type)) {
      return "0L";
    }
    else if (PsiType.DOUBLE.equals(type)) {
      return "0.0";
    }
    else if (PsiType.FLOAT.equals(type)) {
      return "0.0F";
    }
    else if (PsiType.SHORT.equals(type)) {
      return "(short)0";
    }
    else if (PsiType.BYTE.equals(type)) {
      return "(byte)0";
    }
    else if (PsiType.BOOLEAN.equals(type)) {
      return PsiKeyword.FALSE;
    }
    else if (PsiType.CHAR.equals(type)) {
      return "(char)0";
    }
    return PsiKeyword.NULL;
  }
}