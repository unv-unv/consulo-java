/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.java.impl.codeInsight.completion.scope;

import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.MethodSignature;
import com.intellij.java.language.psi.util.MethodSignatureUtil;
import consulo.language.psi.PsiPackage;
import consulo.util.lang.Comparing;
import consulo.util.lang.Pair;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Created by IntelliJ IDEA.
 * User: ik
 * Date: 20.01.2003
 * Time: 16:17:14
 * To change this template use Options | File Templates.
 */
public class CompletionElement {
  private final Object myElement;
  private final PsiSubstitutor mySubstitutor;
  private final Object myEqualityObject;
  private final String myQualifierText;

  public CompletionElement(Object element, PsiSubstitutor substitutor) {
    this(element, substitutor, "");
  }

  public CompletionElement(Object element, PsiSubstitutor substitutor, @Nonnull String qualifierText) {
    myElement = element;
    mySubstitutor = substitutor;
    myQualifierText = qualifierText;
    myEqualityObject = getUniqueId();
  }

  @Nonnull
  public String getQualifierText() {
    return myQualifierText;
  }

  public PsiSubstitutor getSubstitutor() {
    return mySubstitutor;
  }

  public Object getElement() {
    return myElement;
  }

  @Nullable
  private Object getUniqueId() {
    if (myElement instanceof PsiClass) {
      String qName = ((PsiClass) myElement).getQualifiedName();
      return qName == null ? ((PsiClass) myElement).getName() : qName;
    }
    if (myElement instanceof PsiPackage) {
      return ((PsiPackage) myElement).getQualifiedName();
    }
    if (myElement instanceof PsiMethod) {
      return Pair.create(((PsiMethod) myElement).getSignature(mySubstitutor), myQualifierText);
    }
    if (myElement instanceof PsiVariable) {
      return "#" + ((PsiVariable) myElement).getName();
    }

    return null;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof CompletionElement)) {
      return false;
    }

    Object thatObj = ((CompletionElement) obj).myEqualityObject;
    if (myEqualityObject instanceof MethodSignature) {
      return thatObj instanceof MethodSignature && MethodSignatureUtil.METHOD_PARAMETERS_ERASURE_EQUALITY.equals((MethodSignature) myEqualityObject, (MethodSignature) thatObj);
    }
    return Comparing.equal(myEqualityObject, thatObj);
  }

  @Override
  public int hashCode() {
    if (myEqualityObject instanceof MethodSignature) {
      return MethodSignatureUtil.METHOD_PARAMETERS_ERASURE_EQUALITY.hashCode((MethodSignature) myEqualityObject);
    }
    return myEqualityObject != null ? myEqualityObject.hashCode() : 0;
  }

  public boolean isMoreSpecificThan(@Nonnull CompletionElement prev) {
    Object prevElement = prev.getElement();
    if (!(prevElement instanceof PsiMethod && myElement instanceof PsiMethod)) {
      return false;
    }

    PsiType prevType = prev.getSubstitutor().substitute(((PsiMethod) prevElement).getReturnType());
    PsiType candidateType = mySubstitutor.substitute(((PsiMethod) myElement).getReturnType());
    return prevType != null && candidateType != null && !prevType.equals(candidateType) && prevType.isAssignableFrom(candidateType);
  }

}
