/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.java.impl.codeInsight.completion;

import com.intellij.java.impl.codeInsight.ExpectedTypeInfo;
import com.intellij.java.impl.codeInsight.ExpectedTypeInfoImpl;
import com.intellij.java.language.impl.psi.impl.source.resolve.graphInference.FunctionalInterfaceParameterizationUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.language.editor.PsiEquivalenceUtil;
import consulo.language.editor.completion.CompletionParameters;
import consulo.language.editor.completion.CompletionProvider;
import consulo.language.editor.completion.CompletionResultSet;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.TailType;
import consulo.language.psi.PsiElement;
import consulo.language.util.ProcessingContext;
import consulo.logging.Logger;

import jakarta.annotation.Nonnull;
import java.util.Map;
import java.util.function.Consumer;

public class MethodReferenceCompletionProvider implements CompletionProvider {
  private static final Logger LOG = Logger.getInstance(MethodReferenceCompletionProvider.class);

  @Override
  public void addCompletions(@Nonnull CompletionParameters parameters, ProcessingContext context, @Nonnull final CompletionResultSet result) {
    if (!PsiUtil.isLanguageLevel8OrHigher(parameters.getOriginalFile())) {
      return;
    }

    final PsiElement rulezzRef = parameters.getPosition().getParent();
    if (rulezzRef == null || !LambdaUtil.isValidLambdaContext(rulezzRef.getParent())) {
      return;
    }

    final ExpectedTypeInfo[] expectedTypes = JavaSmartCompletionContributor.getExpectedTypes(parameters);
    for (ExpectedTypeInfo expectedType : expectedTypes) {
      final PsiType defaultType = expectedType.getDefaultType();
      if (LambdaUtil.isFunctionalType(defaultType)) {
        final PsiType functionalType = FunctionalInterfaceParameterizationUtil.getGroundTargetType(defaultType);
        final PsiType returnType = LambdaUtil.getFunctionalInterfaceReturnType(functionalType);
        if (returnType != null) {
          final PsiElement position = parameters.getPosition();
          final PsiElement refPlace = position.getParent();
          final ExpectedTypeInfoImpl typeInfo = new ExpectedTypeInfoImpl(returnType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, returnType, TailType.UNKNOWN, null, ExpectedTypeInfoImpl.NULL);
          final Map<PsiElement, PsiType> map = LambdaUtil.getFunctionalTypeMap();
          Consumer<LookupElement> noTypeCheck = new Consumer<LookupElement>() {
            @Override
            public void accept(final LookupElement lookupElement) {
              final PsiElement element = lookupElement.getPsiElement();
              if (element instanceof PsiMethod) {
                final PsiMethodReferenceExpression referenceExpression = createMethodReferenceExpression((PsiMethod) element);
                if (referenceExpression == null) {
                  return;
                }

                final PsiType added = map.put(referenceExpression, functionalType);
                try {
                  final PsiElement resolve = referenceExpression.resolve();
                  if (resolve != null && PsiEquivalenceUtil.areElementsEquivalent(element, resolve) && PsiMethodReferenceUtil.checkMethodReferenceContext(referenceExpression,
                      resolve, functionalType) == null) {
                    result.addElement(new JavaMethodReferenceElement((PsiMethod) element, refPlace));
                  }
                } finally {
                  if (added == null) {
                    map.remove(referenceExpression);
                  }
                }
              }
            }

            private PsiMethodReferenceExpression createMethodReferenceExpression(PsiMethod method) {
              PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(method.getProject());
              if (refPlace instanceof PsiMethodReferenceExpression) {
                final PsiMethodReferenceExpression referenceExpression = (PsiMethodReferenceExpression) refPlace.copy();
                final PsiElement referenceNameElement = referenceExpression.getReferenceNameElement();
                LOG.assertTrue(referenceNameElement != null, referenceExpression);
                referenceNameElement.replace(method.isConstructor() ? elementFactory.createKeyword("new") : elementFactory.createIdentifier(method.getName()));
                return referenceExpression;
              } else if (method.hasModifierProperty(PsiModifier.STATIC)) {
                final PsiClass aClass = method.getContainingClass();
                LOG.assertTrue(aClass != null);
                final String qualifiedName = aClass.getQualifiedName();
                return (PsiMethodReferenceExpression) elementFactory.createExpressionFromText(qualifiedName + "::" + (method.isConstructor() ? "new" : method.getName()), refPlace);
              } else {
                return null;
              }
            }
          };

          final Runnable runnable = ReferenceExpressionCompletionContributor.fillCompletionVariants(new JavaSmartCompletionParameters(parameters, typeInfo), noTypeCheck);
          if (runnable != null) {
            runnable.run();
          }
        }
      }
    }
  }

}
