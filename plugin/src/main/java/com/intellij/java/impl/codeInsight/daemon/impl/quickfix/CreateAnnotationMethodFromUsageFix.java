/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.java.impl.codeInsight.daemon.impl.quickfix;

import com.intellij.java.analysis.impl.codeInsight.daemon.impl.analysis.AnnotationsHighlightUtil;
import com.intellij.java.impl.codeInsight.ExpectedTypeInfo;
import com.intellij.java.impl.codeInsight.ExpectedTypesProvider;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.java.analysis.impl.JavaQuickFixBundle;
import consulo.language.editor.completion.lookup.TailType;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiReference;
import consulo.language.psi.SmartPointerManager;
import consulo.language.psi.SmartPsiElementPointer;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.logging.Logger;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Pair;
import jakarta.annotation.Nonnull;

import jakarta.annotation.Nullable;

public class CreateAnnotationMethodFromUsageFix extends CreateFromUsageBaseFix {
  private static final Logger LOG = Logger.getInstance(CreateAnnotationMethodFromUsageFix.class);

  private final SmartPsiElementPointer<PsiNameValuePair> myNameValuePair;

  public CreateAnnotationMethodFromUsageFix(@Nonnull PsiNameValuePair valuePair) {
    myNameValuePair = SmartPointerManager.getInstance(valuePair.getProject()).createSmartPsiElementPointer(valuePair);
  }

  @Override
  protected boolean isAvailableImpl(int offset) {
    final PsiNameValuePair call = getNameValuePair();
    if (call == null || !call.isValid()) {
      return false;
    }
    String name = call.getName();

    if (name == null || !PsiNameHelper.getInstance(call.getProject()).isIdentifier(name)) {
      return false;
    }
    if (getAnnotationValueType(call.getValue()) == null) {
      return false;
    }
    setText(JavaQuickFixBundle.message("create.method.from.usage.text", name));
    return true;
  }

  @Override
  protected PsiElement getElement() {
    final PsiNameValuePair call = getNameValuePair();
    if (call == null || !call.getManager().isInProject(call)) {
      return null;
    }
    return call;
  }

  @Override
  protected void invokeImpl(final PsiClass targetClass) {
    if (targetClass == null) {
      return;
    }

    PsiNameValuePair nameValuePair = getNameValuePair();
    if (nameValuePair == null || isValidElement(nameValuePair)) {
      return;
    }

    final PsiElementFactory factory = JavaPsiFacade.getInstance(nameValuePair.getProject()).getElementFactory();

    final String methodName = nameValuePair.getName();
    LOG.assertTrue(methodName != null);

    PsiMethod method = factory.createMethod(methodName, PsiType.VOID);

    method = (PsiMethod) targetClass.add(method);

    PsiCodeBlock body = method.getBody();
    assert body != null;
    body.delete();

    final PsiElement context = PsiTreeUtil.getParentOfType(nameValuePair, PsiClass.class, PsiMethod.class);

    final PsiType type = getAnnotationValueType(nameValuePair.getValue());
    LOG.assertTrue(type != null);
    final ExpectedTypeInfo[] expectedTypes = new ExpectedTypeInfo[]{ExpectedTypesProvider.createInfo(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, TailType.NONE)};
    CreateMethodFromUsageFix.doCreate(targetClass, method, true, ContainerUtil.map2List(PsiExpression.EMPTY_ARRAY, Pair.<PsiExpression, PsiType>createFunction(null)), getTargetSubstitutor
        (nameValuePair), expectedTypes, context);
  }

  @Nullable
  public static PsiType getAnnotationValueType(PsiAnnotationMemberValue value) {
    PsiType type = null;
    if (value instanceof PsiExpression) {
      type = ((PsiExpression) value).getType();
    } else if (value instanceof PsiArrayInitializerMemberValue) {
      final PsiAnnotationMemberValue[] initializers = ((PsiArrayInitializerMemberValue) value).getInitializers();
      PsiType currentType = null;
      for (PsiAnnotationMemberValue initializer : initializers) {
        if (initializer instanceof PsiArrayInitializerMemberValue) {
          return null;
        }
        if (!(initializer instanceof PsiExpression)) {
          return null;
        }
        final PsiType psiType = ((PsiExpression) initializer).getType();
        if (psiType != null) {
          if (currentType == null) {
            currentType = psiType;
          } else {
            if (!TypeConversionUtil.isAssignable(currentType, psiType)) {
              if (TypeConversionUtil.isAssignable(psiType, currentType)) {
                currentType = psiType;
              } else {
                return null;
              }
            }
          }
        }
      }
      if (currentType != null) {
        type = currentType.createArrayType();
      }
    }
    if (type != null && type.accept(AnnotationsHighlightUtil.AnnotationReturnTypeVisitor.INSTANCE).booleanValue()) {
      return type;
    }
    return null;
  }


  @Override
  protected boolean isValidElement(PsiElement element) {
    final PsiReference reference = element.getReference();
    return reference != null && reference.resolve() != null;
  }

  @Nullable
  protected PsiNameValuePair getNameValuePair() {
    return myNameValuePair.getElement();
  }
}
