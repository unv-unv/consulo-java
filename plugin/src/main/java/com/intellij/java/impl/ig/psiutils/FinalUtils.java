/*
 * Copyright 2009-2011 Bas Leijdekkers
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

import com.intellij.java.analysis.impl.codeInsight.daemon.impl.analysis.HighlightControlFlowUtil;
import com.intellij.java.language.impl.psi.controlFlow.ControlFlowUtil;
import com.intellij.java.language.impl.psi.controlFlow.LocalsOrMyInstanceFieldsControlFlowPolicy;
import com.intellij.java.language.psi.PsiField;
import com.intellij.java.language.psi.PsiParameter;
import com.intellij.java.language.psi.PsiReferenceExpression;
import com.intellij.java.language.psi.PsiVariable;
import com.intellij.java.language.psi.util.PsiUtil;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import consulo.language.editor.rawHighlight.HighlightInfo;
import consulo.language.psi.PsiElement;
import consulo.language.psi.resolve.PsiElementProcessor;
import consulo.language.psi.util.PsiTreeUtil;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class FinalUtils {

  private FinalUtils() {
  }

  public static boolean canBeFinal(PsiVariable variable) {
    if (variable.getInitializer() != null || variable instanceof PsiParameter) {
      // parameters have an implicit initializer
      return !VariableAccessUtils.variableIsAssigned(variable);
    }
    if (variable instanceof PsiField && !HighlightControlFlowUtil.isFieldInitializedAfterObjectConstruction((PsiField) variable)) {
      return false;
    }
    PsiElement scope = variable instanceof PsiField
        ? PsiUtil.getTopLevelClass(variable)
        : PsiUtil.getVariableCodeBlock(variable, null);
    if (scope == null) {
      return false;
    }
    Map<PsiElement, Collection<ControlFlowUtil.VariableInfo>> finalVarProblems = new HashMap<>();
    Map<PsiElement, Collection<PsiReferenceExpression>> uninitializedVarProblems = new HashMap<>();
    PsiElementProcessor<PsiElement> elementDoesNotViolateFinality = e -> {
      if (!(e instanceof PsiReferenceExpression)) {
        return true;
      }
      PsiReferenceExpression ref = (PsiReferenceExpression) e;
      if (!ref.isReferenceTo(variable)) {
        return true;
      }
      HighlightInfo highlightInfo = HighlightControlFlowUtil
          .checkVariableInitializedBeforeUsage(ref, variable, uninitializedVarProblems, variable.getContainingFile(), true);
      if (highlightInfo != null) {
        return false;
      }
      if (!PsiUtil.isAccessedForWriting(ref)) {
        return true;
      }
      if (!LocalsOrMyInstanceFieldsControlFlowPolicy.isLocalOrMyInstanceReference(ref)) {
        return false;
      }
      if (ControlFlowUtil.isVariableAssignedInLoop(ref, variable)) {
        return false;
      }
      if (variable instanceof PsiField) {
        if (PsiUtil.findEnclosingConstructorOrInitializer(ref) == null) {
          return false;
        }
        PsiElement innerClass = HighlightControlFlowUtil.getInnerClassVariableReferencedFrom(variable, ref);
        if (innerClass != null && innerClass != ((PsiField) variable).getContainingClass()) {
          return false;
        }
      }
      return HighlightControlFlowUtil.checkFinalVariableMightAlreadyHaveBeenAssignedTo(variable, ref, finalVarProblems) == null;
    };
    return PsiTreeUtil.processElements(scope, elementDoesNotViolateFinality);
  }
}
