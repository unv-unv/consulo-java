/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.changeSignature;

import com.intellij.java.impl.refactoring.util.CanonicalTypes;
import com.intellij.java.language.psi.*;
import consulo.language.editor.refactoring.changeSignature.ChangeInfo;
import jakarta.annotation.Nonnull;

import java.util.Collection;

/**
 * @author Maxim.Medvedev
 */
public interface JavaChangeInfo extends ChangeInfo {
  boolean isVisibilityChanged();

  boolean isExceptionSetChanged();

  boolean isExceptionSetOrOrderChanged();

  PsiMethod getMethod();

  CanonicalTypes.Type getNewReturnType();

  @Nonnull
  JavaParameterInfo[] getNewParameters();

  @PsiModifier.ModifierConstant
  String getNewVisibility();

  @Nonnull
  String[] getOldParameterNames();

  @Nonnull
  String[] getOldParameterTypes();

  ThrownExceptionInfo[] getNewExceptions();

  boolean isRetainsVarargs();

  boolean isObtainsVarags();

  boolean isArrayToVarargs();

  PsiIdentifier getNewNameIdentifier();

  String getOldName();

  boolean wasVararg();

  boolean[] toRemoveParm();

  PsiExpression getValue(int i, PsiCallExpression callExpression);

  void updateMethod(PsiMethod psiMethod);

  Collection<PsiMethod> getMethodsToPropagateParameters();
}
