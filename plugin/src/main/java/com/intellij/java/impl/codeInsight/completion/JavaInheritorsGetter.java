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
package com.intellij.java.impl.codeInsight.completion;

import com.intellij.java.analysis.impl.codeInsight.daemon.impl.analysis.HighlightClassUtil;
import com.intellij.java.impl.codeInsight.CodeInsightUtil;
import com.intellij.java.impl.codeInsight.ExpectedTypeInfo;
import com.intellij.java.impl.codeInsight.lookup.PsiTypeLookupItem;
import com.intellij.java.impl.codeInsight.lookup.TypedLookupItem;
import com.intellij.java.impl.psi.filters.getters.ExpectedTypesGetter;
import com.intellij.java.impl.psi.statistics.JavaStatisticsManager;
import com.intellij.java.language.LanguageLevel;
import com.intellij.java.language.impl.psi.PsiDiamondTypeImpl;
import com.intellij.java.language.impl.psi.impl.PsiClassImplUtil;
import com.intellij.java.language.impl.psi.impl.source.PsiClassReferenceType;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiTypesUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.application.util.function.Processor;
import consulo.application.util.matcher.PrefixMatcher;
import consulo.language.editor.completion.AutoCompletionPolicy;
import consulo.language.editor.completion.CompletionParameters;
import consulo.language.editor.completion.CompletionProvider;
import consulo.language.editor.completion.CompletionResultSet;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.LookupElementDecorator;
import consulo.language.psi.PsiCompiledElement;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.statistician.StatisticsInfo;
import consulo.language.statistician.StatisticsManager;
import consulo.language.util.IncorrectOperationException;
import consulo.language.util.ProcessingContext;
import consulo.logging.Logger;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.SmartList;
import consulo.util.lang.ObjectUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.intellij.java.language.patterns.PsiJavaPatterns.psiElement;

/**
 * @author peter
 */
public class JavaInheritorsGetter implements CompletionProvider {
  private static final Logger LOG = Logger.getInstance(JavaInheritorsGetter.class);
  private final ConstructorInsertHandler myConstructorInsertHandler;

  public JavaInheritorsGetter(final ConstructorInsertHandler constructorInsertHandler) {
    myConstructorInsertHandler = constructorInsertHandler;
  }

  private static boolean shouldAddArrayInitializer(PsiElement position) {
    if (!JavaCompletionContributor.isInJavaContext(position) || !JavaSmartCompletionContributor.AFTER_NEW.accepts(position)) {
      return false;
    }
    PsiNewExpression newExpression = PsiTreeUtil.getParentOfType(position, PsiNewExpression.class);
    return newExpression != null && newExpression.getParent() instanceof PsiExpressionList;
  }

  @Override
  public void addCompletions(@Nonnull final CompletionParameters parameters, final ProcessingContext matchingContext, @Nonnull final CompletionResultSet result) {
    final ExpectedTypeInfo[] infos = JavaSmartCompletionContributor.getExpectedTypes(parameters);

    final List<ExpectedTypeInfo> infoCollection = Arrays.asList(infos);
    generateVariants(parameters, result.getPrefixMatcher(), infos, lookupElement -> result.addElement(JavaSmartCompletionContributor.decorate(lookupElement, infoCollection)));
  }

  public void generateVariants(final CompletionParameters parameters, final PrefixMatcher prefixMatcher, final Consumer<LookupElement> consumer) {
    generateVariants(parameters, prefixMatcher, JavaSmartCompletionContributor.getExpectedTypes(parameters), consumer);
  }

  private void generateVariants(final CompletionParameters parameters, final PrefixMatcher prefixMatcher, final ExpectedTypeInfo[] infos, final Consumer<LookupElement> consumer) {

    addArrayTypes(parameters.getPosition(), infos, consumer);

    List<PsiClassType> classTypes = extractClassTypes(infos);
    boolean arraysWelcome = ContainerUtil.exists(ExpectedTypesGetter.extractTypes(infos, true), t -> t.getDeepComponentType().equalsToText(CommonClassNames.JAVA_LANG_OBJECT));
    processInheritors(parameters, classTypes, prefixMatcher, type ->
    {
      final LookupElement element = addExpectedType(type, parameters);
      if (element != null) {
        Supplier<PsiClassType> itemType = () -> (PsiClassType) ObjectUtil.assertNotNull(element.as(TypedLookupItem.CLASS_CONDITION_KEY)).getType();
        JavaConstructorCallElement.wrap(element, (PsiClass) element.getObject(), parameters.getPosition(), itemType).forEach(consumer::accept);
      }
      if (arraysWelcome) {
        consumer.accept(createNewArrayItem(parameters.getPosition(), type.createArrayType()));
      }
    });
  }

  private static void addArrayTypes(PsiElement identifierCopy, ExpectedTypeInfo[] infos, final Consumer<LookupElement> consumer) {
    for (final PsiType type : ExpectedTypesGetter.extractTypes(infos, true)) {
      if (type instanceof PsiArrayType) {
        consumer.accept(createNewArrayItem(identifierCopy, type));

        if (shouldAddArrayInitializer(identifierCopy)) {
          PsiTypeLookupItem item = createNewArrayItem(identifierCopy, type);
          item.setAddArrayInitializer();
          consumer.accept(item);
        }
      }
    }
  }

  private static PsiTypeLookupItem createNewArrayItem(PsiElement context, PsiType type) {
    return PsiTypeLookupItem.createLookupItem(TypeConversionUtil.erasure(GenericsUtil.getVariableTypeByExpressionType(type)), context).setShowPackage();
  }

  private static List<PsiClassType> extractClassTypes(ExpectedTypeInfo[] infos) {
    final List<PsiClassType> expectedClassTypes = new SmartList<>();
    for (PsiType type : ExpectedTypesGetter.extractTypes(infos, true)) {
      if (type instanceof PsiClassType) {
        final PsiClassType classType = (PsiClassType) type;
        if (classType.resolve() != null) {
          expectedClassTypes.add(classType);
        }
      }
    }
    return expectedClassTypes;
  }

  @Nullable
  private LookupElement addExpectedType(final PsiType type, final CompletionParameters parameters) {
    if (!JavaCompletionUtil.hasAccessibleConstructor(type, parameters.getPosition())) {
      return null;
    }

    final PsiClass psiClass = PsiUtil.resolveClassInType(type);
    if (psiClass == null || psiClass.getName() == null) {
      return null;
    }

    PsiElement position = parameters.getPosition();
    if ((parameters.getInvocationCount() < 2 || psiClass instanceof PsiCompiledElement) && HighlightClassUtil.checkCreateInnerClassFromStaticContext(position, null, psiClass) != null &&
        !psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW).afterLeaf(".")).accepts(position)) {
      return null;
    }

    PsiType psiType = GenericsUtil.eliminateWildcards(type);
    if (JavaSmartCompletionContributor.AFTER_NEW.accepts(parameters.getOriginalPosition()) && PsiUtil.getLanguageLevel(parameters.getOriginalFile()).isAtLeast(LanguageLevel.JDK_1_7)) {
      final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(psiClass.getProject());
      if (psiClass.hasTypeParameters() && !((PsiClassType) type).isRaw()) {
        final String erasedText = TypeConversionUtil.erasure(psiType).getCanonicalText();
        String canonicalText = psiType.getCanonicalText();
        if (canonicalText.contains("?extends") || canonicalText.contains("?super")) {
          LOG.error("Malformed canonical text: " + canonicalText + "; presentable text: " + psiType + " of " + psiType.getClass() + "; " + (psiType instanceof PsiClassReferenceType ? (
              (PsiClassReferenceType) psiType).getReference().getClass() : ""));
          return null;
        }
        try {
          final PsiStatement statement = elementFactory.createStatementFromText(canonicalText + " v = new " + erasedText + "<>()", parameters.getOriginalFile());
          final PsiVariable declaredVar = (PsiVariable) ((PsiDeclarationStatement) statement).getDeclaredElements()[0];
          final PsiNewExpression initializer = (PsiNewExpression) declaredVar.getInitializer();
          final boolean hasDefaultConstructorOrNoGenericsOne = PsiDiamondTypeImpl.hasDefaultConstructor(psiClass) || !PsiDiamondTypeImpl.haveConstructorsGenericsParameters(psiClass);
          if (hasDefaultConstructorOrNoGenericsOne) {
            final PsiDiamondTypeImpl.DiamondInferenceResult inferenceResult = PsiDiamondTypeImpl.resolveInferredTypes(initializer);
            if (inferenceResult.getErrorMessage() == null && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT) && areInferredTypesApplicable(inferenceResult.getTypes(), parameters
                .getPosition())) {
              psiType = initializer.getType();
            }
          }
        } catch (IncorrectOperationException ignore) {
        }
      }
    }
    final PsiTypeLookupItem item = PsiTypeLookupItem.createLookupItem(psiType, position).setShowPackage();

    if (psiClass.isInterface() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
      item.setAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE);
      item.setIndicateAnonymous(true);
    }

    return LookupElementDecorator.withInsertHandler(item, myConstructorInsertHandler);
  }

  private static boolean areInferredTypesApplicable(@Nonnull PsiType[] types, PsiElement position) {
    final PsiNewExpression newExpression = PsiTreeUtil.getParentOfType(position, PsiNewExpression.class, false);
    if (!PsiUtil.isLanguageLevel8OrHigher(position)) {
      return newExpression != null && PsiTypesUtil.getExpectedTypeByParent(newExpression) != null;
    }
    final PsiCallExpression methodCallExpression = PsiTreeUtil.getParentOfType(newExpression, PsiCallExpression.class, false, PsiStatement.class);
    if (methodCallExpression != null && PsiUtil.isLanguageLevel8OrHigher(methodCallExpression)) {
      PsiElement parent = PsiUtil.skipParenthesizedExprUp(newExpression.getParent());
      PsiExpressionList argumentList = methodCallExpression.getArgumentList();
      final int idx = argumentList != null ? ArrayUtil.find(argumentList.getExpressions(), parent) : -1;
      if (idx > -1) {
        final JavaResolveResult resolveResult = methodCallExpression.resolveMethodGenerics();
        final PsiMethod method = (PsiMethod) resolveResult.getElement();
        if (method != null) {
          final PsiParameter[] parameters = method.getParameterList().getParameters();
          if (idx < parameters.length) {
            final PsiType expectedType = resolveResult.getSubstitutor().substitute(parameters[idx].getType());
            final PsiClass aClass = PsiUtil.resolveClassInType(expectedType);
            if (aClass != null) {
              final PsiClassType inferredArg = JavaPsiFacade.getElementFactory(method.getProject()).createType(aClass, types);
              return TypeConversionUtil.isAssignable(expectedType, inferredArg);
            }
          }
        }
      }
    }
    return true;
  }

  public static void processInheritors(final CompletionParameters parameters, Collection<PsiClassType> expectedClassTypes, final PrefixMatcher matcher, final Consumer<PsiType> consumer) {
    final PsiElement context = parameters.getPosition();
    GlobalSearchScope scope = context.getResolveScope();
    expectedClassTypes = ContainerUtil.mapNotNull(expectedClassTypes, type -> PsiClassImplUtil.correctType(type, scope));

    //quick
    if (!processMostProbableInheritors(parameters.getOriginalFile(), context, expectedClassTypes, consumer)) {
      return;
    }

    //long
    for (final PsiClassType type : expectedClassTypes) {
      CodeInsightUtil.processSubTypes(type, context, false, matcher, consumer);
    }
  }

  private static boolean processMostProbableInheritors(PsiFile contextFile, PsiElement context, Collection<PsiClassType> expectedClassTypes, Consumer<PsiType> consumer) {
    for (final PsiClassType type : expectedClassTypes) {
      consumer.accept(type);

      final PsiClassType.ClassResolveResult baseResult = JavaCompletionUtil.originalize(type).resolveGenerics();
      final PsiClass baseClass = baseResult.getElement();
      if (baseClass == null) {
        return false;
      }

      final PsiSubstitutor baseSubstitutor = baseResult.getSubstitutor();

      final Processor<PsiClass> processor = CodeInsightUtil.createInheritorsProcessor(context, type, 0, false, consumer, baseClass, baseSubstitutor);
      final StatisticsInfo[] stats = StatisticsManager.getInstance().getAllValues(JavaStatisticsManager.getAfterNewKey(type));
      for (final StatisticsInfo statisticsInfo : stats) {
        final String value = statisticsInfo.getValue();
        if (value.startsWith(JavaStatisticsManager.CLASS_PREFIX)) {
          final String qname = value.substring(JavaStatisticsManager.CLASS_PREFIX.length());
          final PsiClass psiClass = JavaPsiFacade.getInstance(contextFile.getProject()).findClass(qname, contextFile.getResolveScope());
          if (psiClass != null && !PsiTreeUtil.isAncestor(contextFile, psiClass, true) && !processor.process(psiClass)) {
            break;
          }
        }
      }
    }
    return true;
  }
}
