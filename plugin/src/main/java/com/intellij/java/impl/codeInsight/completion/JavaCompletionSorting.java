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
import com.intellij.java.impl.codeInsight.lookup.PsiTypeLookupItem;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.codeStyle.VariableKind;
import com.intellij.java.language.psi.javadoc.PsiDocComment;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.application.util.matcher.NameUtil;
import consulo.ide.impl.idea.codeInsight.completion.impl.CompletionSorterImpl;
import consulo.ide.impl.idea.codeInsight.completion.impl.LiftShorterItemsClassifier;
import consulo.ide.impl.idea.codeInsight.lookup.ClassifierFactory;
import consulo.language.editor.completion.*;
import consulo.language.editor.completion.lookup.Classifier;
import consulo.language.editor.completion.lookup.LookupElement;
import consulo.language.editor.completion.lookup.LookupElementWeigher;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.module.content.ProjectFileIndex;
import consulo.module.content.ProjectRootManager;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.SmartList;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

import static com.intellij.java.language.patterns.PsiJavaPatterns.psiElement;

/**
 * @author peter
 */
public class JavaCompletionSorting {
  private JavaCompletionSorting() {
  }

  public static CompletionResultSet addJavaSorting(final CompletionParameters parameters, CompletionResultSet result) {
    PsiElement position = parameters.getPosition();
    ExpectedTypeInfo[] expectedTypes = getExpectedTypesWithDfa(parameters, position);
    CompletionType type = parameters.getCompletionType();
    boolean smart = type == CompletionType.SMART;
    boolean afterNew = JavaSmartCompletionContributor.AFTER_NEW.accepts(position);

    List<LookupElementWeigher> afterProximity = new ArrayList<>();
    afterProximity.add(new PreferContainingSameWords(expectedTypes));
    afterProximity.add(new PreferShorter(expectedTypes));

    CompletionSorter sorter = CompletionSorter.defaultSorter(parameters, result.getPrefixMatcher());
    if (!smart && afterNew) {
      sorter = sorter.weighBefore("liftShorter", new PreferExpected(true, expectedTypes, position));
    } else if (PsiTreeUtil.getParentOfType(position, PsiReferenceList.class) == null) {
      sorter = ((CompletionSorterImpl) sorter).withClassifier("liftShorterClasses", true, new LiftShorterClasses(position));
    }
    if (smart) {
      sorter = sorter.weighAfter("priority", new PreferDefaultTypeWeigher(expectedTypes, parameters));
    }

    final PreferMostUsedWeigher preferMostUsedWeigher = PreferMostUsedWeigher.create(position);
    List<LookupElementWeigher> afterStats = ContainerUtil.newArrayList();
    afterStats.add(new PreferByKindWeigher(type, position, expectedTypes));
    if (!smart) {
      if (preferMostUsedWeigher == null) {
        ContainerUtil.addIfNotNull(afterStats, preferStatics(position, expectedTypes));
      }
      if (!afterNew) {
        afterStats.add(new PreferExpected(false, expectedTypes, position));
      }
    }
    if (preferMostUsedWeigher != null) {
      afterStats.add(preferMostUsedWeigher);
      ContainerUtil.addIfNotNull(afterStats, preferStatics(position, expectedTypes));
    }
    ContainerUtil.addIfNotNull(afterStats, recursion(parameters, expectedTypes));
    afterStats.add(new PreferSimilarlyEnding(expectedTypes));
    if (ContainerUtil.or(expectedTypes, info -> !info.getType().equals(PsiType.VOID))) {
      afterStats.add(new PreferNonGeneric());
    }
    Collections.addAll(afterStats, new PreferAccessible(position), new PreferSimple());

    sorter = sorter.weighAfter("stats", afterStats.toArray(new LookupElementWeigher[afterStats.size()]));
    sorter = sorter.weighAfter("proximity", afterProximity.toArray(new LookupElementWeigher[afterProximity.size()]));
    return result.withRelevanceSorter(sorter);
  }

  @Nonnull
  private static ExpectedTypeInfo[] getExpectedTypesWithDfa(CompletionParameters parameters, PsiElement position) {
    if (psiElement().beforeLeaf(psiElement().withText(".")).accepts(position)) {
      return ExpectedTypeInfo.EMPTY_ARRAY;
    }

    List<ExpectedTypeInfo> castExpectation = SmartCastProvider.getParenthesizedCastExpectationByOperandType(position);
    if (!castExpectation.isEmpty()) {
      return castExpectation.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
    }
    return JavaSmartCompletionContributor.getExpectedTypes(parameters);
  }

  @Nullable
  private static LookupElementWeigher recursion(CompletionParameters parameters, final ExpectedTypeInfo[] expectedInfos) {
    final PsiElement position = parameters.getPosition();
    final PsiMethodCallExpression expression = PsiTreeUtil.getParentOfType(position, PsiMethodCallExpression.class, true, PsiClass.class);
    final PsiReferenceExpression reference = expression != null ? expression.getMethodExpression() : PsiTreeUtil.getParentOfType(position, PsiReferenceExpression.class);
    if (reference == null) {
      return null;
    }

    return new RecursionWeigher(position, parameters.getCompletionType(), reference, expression, expectedInfos);
  }

  @Nullable
  private static LookupElementWeigher preferStatics(PsiElement position, final ExpectedTypeInfo[] infos) {
    if (PsiTreeUtil.getParentOfType(position, PsiDocComment.class) != null) {
      return null;
    }
    if (position.getParent() instanceof PsiReferenceExpression) {
      final PsiReferenceExpression refExpr = (PsiReferenceExpression) position.getParent();
      final PsiElement qualifier = refExpr.getQualifier();
      if (qualifier == null) {
        return null;
      }
      if (!(qualifier instanceof PsiJavaCodeReferenceElement) || !(((PsiJavaCodeReferenceElement) qualifier).resolve() instanceof PsiClass)) {
        return null;
      }
    }

    return new LookupElementWeigher("statics") {
      @Nonnull
      @Override
      public Comparable weigh(@Nonnull LookupElement element) {
        final Object o = element.getObject();
        if (o instanceof PsiKeyword) {
          return -3;
        }
        if (!(o instanceof PsiMember) || element.getUserData(JavaGenerateMemberCompletionContributor.GENERATE_ELEMENT) != null) {
          return 0;
        }

        if (((PsiMember) o).hasModifierProperty(PsiModifier.STATIC) && !hasNonVoid(infos)) {
          if (o instanceof PsiMethod) {
            return -5;
          }
          if (o instanceof PsiField) {
            return -4;
          }
        }

        if (o instanceof PsiClass) {
          return -3;
        }

        //instance method or field
        return -5;
      }
    };
  }

  private static ExpectedTypeMatching getExpectedTypeMatching(LookupElement item, ExpectedTypeInfo[] expectedInfos, @Nullable String expectedMemberName) {
    PsiType itemType = JavaCompletionUtil.getLookupElementType(item);

    if (itemType != null) {
      PsiUtil.ensureValidType(itemType);

      for (final ExpectedTypeInfo expectedInfo : expectedInfos) {
        PsiType expectedType = expectedInfo.getType();

        if (expectedInfo.getKind() == ExpectedTypeInfo.TYPE_OR_SUPERTYPE) {
          if (itemType.isAssignableFrom(expectedType)) {
            return ExpectedTypeMatching.expected;
          }
        } else {
          PsiType defaultType = expectedInfo.getDefaultType();
          if (defaultType != expectedType && defaultType.isAssignableFrom(itemType)) {
            return ExpectedTypeMatching.ofDefaultType;
          }
          if (expectedType.isAssignableFrom(itemType)) {
            return ExpectedTypeMatching.expected;
          }
        }
      }
    }

    if (hasNonVoid(expectedInfos)) {
      if (item.getObject() instanceof PsiKeyword) {
        String keyword = ((PsiKeyword) item.getObject()).getText();
        if (PsiKeyword.NEW.equals(keyword) || PsiKeyword.NULL.equals(keyword)) {
          return ExpectedTypeMatching.maybeExpected;
        }
      }
    } else if (expectedInfos.length > 0) {
      return ExpectedTypeMatching.unexpected;
    }

    return preferByMemberName(expectedMemberName, itemType);
  }

  @Nonnull
  private static ExpectedTypeMatching preferByMemberName(@Nullable String expectedMemberName, @Nullable PsiType itemType) {
    if (expectedMemberName != null) {
      PsiClass itemClass = PsiUtil.resolveClassInClassTypeOnly(itemType);
      if (itemClass != null) {
        if (itemClass.findMethodsByName(expectedMemberName, true).length > 0 || itemClass.findFieldByName(expectedMemberName, true) != null || itemClass.findInnerClassByName
            (expectedMemberName, true) != null) {
          return ExpectedTypeMatching.expected;
        }
      }
    }

    return ExpectedTypeMatching.normal;
  }

  private static boolean hasNonVoid(ExpectedTypeInfo[] expectedInfos) {
    boolean hasNonVoid = false;
    for (ExpectedTypeInfo info : expectedInfos) {
      if (!PsiType.VOID.equals(info.getType())) {
        hasNonVoid = true;
      }
    }
    return hasNonVoid;
  }

  @Nullable
  private static String getLookupObjectName(Object o) {
    if (o instanceof PsiVariable) {
      final PsiVariable variable = (PsiVariable) o;
      JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(variable.getProject());
      VariableKind variableKind = codeStyleManager.getVariableKind(variable);
      return codeStyleManager.variableNameToPropertyName(variable.getName(), variableKind);
    }
    if (o instanceof PsiMethod) {
      return ((PsiMethod) o).getName();
    }
    return null;
  }

  private static int getNameEndMatchingDegree(final String name, ExpectedTypeInfo[] expectedInfos) {
    int res = 0;
    if (name != null && expectedInfos != null) {
      final List<String> words = NameUtil.nameToWordsLowerCase(name);
      final List<String> wordsNoDigits = NameUtil.nameToWordsLowerCase(truncDigits(name));
      int max1 = calcMatch(words, 0, expectedInfos);
      max1 = calcMatch(wordsNoDigits, max1, expectedInfos);
      res = max1;
    }

    return res;
  }

  private static String truncDigits(String name) {
    int count = name.length() - 1;
    while (count >= 0) {
      char c = name.charAt(count);
      if (!Character.isDigit(c)) {
        break;
      }
      count--;
    }
    return name.substring(0, count + 1);
  }

  private static int calcMatch(final List<String> words, int max, ExpectedTypeInfo[] myExpectedInfos) {
    for (ExpectedTypeInfo myExpectedInfo : myExpectedInfos) {
      String expectedName = ((ExpectedTypeInfoImpl) myExpectedInfo).getExpectedName();
      if (expectedName == null) {
        continue;
      }
      max = calcMatch(expectedName, words, max);
      max = calcMatch(truncDigits(expectedName), words, max);
    }
    return max;
  }

  private static int calcMatch(final String expectedName, final List<String> words, int max) {
    if (expectedName == null) {
      return max;
    }

    String[] expectedWords = NameUtil.nameToWords(expectedName);
    int limit = Math.min(words.size(), expectedWords.length);
    for (int i = 0; i < limit; i++) {
      String word = words.get(words.size() - i - 1);
      String expectedWord = expectedWords[expectedWords.length - i - 1];
      if (word.equalsIgnoreCase(expectedWord)) {
        max = Math.max(max, i + 1);
      } else {
        break;
      }
    }
    return max;
  }

  private static class PreferDefaultTypeWeigher extends LookupElementWeigher {
    private final PsiTypeParameter myTypeParameter;
    private final ExpectedTypeInfo[] myExpectedTypes;
    private final CompletionParameters myParameters;
    private final CompletionLocation myLocation;

    public PreferDefaultTypeWeigher(ExpectedTypeInfo[] expectedTypes, CompletionParameters parameters) {
      super("defaultType");
      myExpectedTypes = expectedTypes == null ? null : ContainerUtil.map2Array(expectedTypes, ExpectedTypeInfo.class, info ->
      {
        PsiType type = removeClassWildcard(info.getType());
        PsiType defaultType = removeClassWildcard(info.getDefaultType());
        if (type == info.getType() && defaultType == info.getDefaultType()) {
          return info;
        }
        return new ExpectedTypeInfoImpl(type, info.getKind(), defaultType, info.getTailType(), null, ExpectedTypeInfoImpl.NULL);
      });
      myParameters = parameters;

      final Pair<PsiTypeParameterListOwner, Integer> pair = TypeArgumentCompletionProvider.getTypeParameterInfo(parameters.getPosition());
      myTypeParameter = pair == null ? null : pair.first.getTypeParameters()[pair.second.intValue()];
      myLocation = new CompletionLocation(myParameters);
    }

    @Nonnull
    @Override
    public MyResult weigh(@Nonnull LookupElement item) {
      final Object object = item.getObject();

      if (object instanceof PsiClass) {
        if (object instanceof PsiTypeParameter) {
          return MyResult.typeParameter;
        }

        if (myTypeParameter != null && object.equals(PsiUtil.resolveClassInType(TypeConversionUtil.typeParameterErasure(myTypeParameter)))) {
          return MyResult.exactlyExpected;
        }
      }

      if (myExpectedTypes == null) {
        return MyResult.normal;
      }

      PsiType itemType = JavaCompletionUtil.getLookupElementType(item);
      if (itemType == null || !itemType.isValid()) {
        return MyResult.normal;
      }

      if (object instanceof PsiClass) {
        for (final ExpectedTypeInfo info : myExpectedTypes) {
          if (TypeConversionUtil.erasure(info.getType().getDeepComponentType()).equals(TypeConversionUtil.erasure(itemType))) {
            return AbstractExpectedTypeSkipper.skips(item, myLocation) ? MyResult.expectedNoSelect : MyResult.exactlyExpected;
          }
        }
      }

      for (final ExpectedTypeInfo expectedInfo : myExpectedTypes) {
        final PsiType defaultType = expectedInfo.getDefaultType();
        final PsiType expectedType = expectedInfo.getType();
        if (!expectedType.isValid()) {
          return MyResult.normal;
        }

        if (defaultType != expectedType) {
          if (defaultType.equals(itemType)) {
            return MyResult.exactlyDefault;
          }

          if (defaultType.isAssignableFrom(itemType)) {
            return MyResult.ofDefaultType;
          }
        }
        if (PsiType.VOID.equals(itemType) && PsiType.VOID.equals(expectedType)) {
          return MyResult.exactlyExpected;
        }
      }

      return MyResult.normal;
    }

    private static PsiType removeClassWildcard(PsiType type) {
      if (type instanceof PsiClassType) {
        final PsiClass psiClass = ((PsiClassType) type).resolve();
        if (psiClass != null && CommonClassNames.JAVA_LANG_CLASS.equals(psiClass.getQualifiedName())) {
          PsiClassType erased = (PsiClassType) GenericsUtil.eliminateWildcards(type);
          PsiType[] parameters = erased.getParameters();
          if (parameters.length == 1 && !parameters[0].equalsToText(CommonClassNames.JAVA_LANG_OBJECT)) {
            return erased;
          }
        }
      }
      return type;
    }

    private enum MyResult {
      expectedNoSelect,
      typeParameter,
      exactlyDefault,
      ofDefaultType,
      exactlyExpected,
      normal,
    }

  }

  private enum ExpectedTypeMatching {
    ofDefaultType,
    expected,
    maybeExpected,
    normal,
    unexpected,
  }

  private static class PreferAccessible extends LookupElementWeigher {
    private final PsiElement myPosition;

    public PreferAccessible(PsiElement position) {
      super("accessible");
      myPosition = position;
    }

    private enum MyEnum {
      NORMAL,
      DEPRECATED,
      INACCESSIBLE,
    }

    @Nonnull
    @Override
    public Comparable weigh(@Nonnull LookupElement element) {
      final Object object = element.getObject();
      if (object instanceof PsiDocCommentOwner) {
        final PsiDocCommentOwner member = (PsiDocCommentOwner) object;
        if (!JavaPsiFacade.getInstance(member.getProject()).getResolveHelper().isAccessible(member, myPosition, null)) {
          return MyEnum.INACCESSIBLE;
        }
        if (JavaCompletionUtil.isEffectivelyDeprecated(member)) {
          return MyEnum.DEPRECATED;
        }
      }
      return MyEnum.NORMAL;
    }
  }

  private static class PreferNonGeneric extends LookupElementWeigher {
    public PreferNonGeneric() {
      super("nonGeneric");
    }

    @Nonnull
    @Override
    public Comparable weigh(@Nonnull LookupElement element) {
      final Object object = element.getObject();
      if (object instanceof PsiMethod) {
        PsiType type = ((PsiMethod) object).getReturnType();
        final JavaMethodCallElement callItem = element.as(JavaMethodCallElement.CLASS_CONDITION_KEY);
        if (callItem != null) {
          type = callItem.getSubstitutor().substitute(type);
        }

        if (type instanceof PsiClassType && ((PsiClassType) type).resolve() instanceof PsiTypeParameter) {
          return 1;
        }
      }

      return 0;
    }
  }

  private static class PreferSimple extends LookupElementWeigher {
    public PreferSimple() {
      super("simple");
    }

    @Nonnull
    @Override
    public Comparable weigh(@Nonnull LookupElement element) {
      final PsiTypeLookupItem lookupItem = element.as(PsiTypeLookupItem.CLASS_CONDITION_KEY);
      if (lookupItem != null) {
        return lookupItem.getBracketsCount() * 10 + (lookupItem.isAddArrayInitializer() ? 1 : 0);
      }
      return 0;
    }
  }

  private static class PreferExpected extends LookupElementWeigher {
    private final boolean myConstructorPossible;
    private final ExpectedTypeInfo[] myExpectedTypes;
    private final List<PsiType> myExpectedClasses = new SmartList<>();
    private final String myExpectedMemberName;

    public PreferExpected(boolean constructorPossible, ExpectedTypeInfo[] expectedTypes, PsiElement position) {
      super("expectedType");
      myConstructorPossible = constructorPossible;
      myExpectedTypes = expectedTypes;
      for (ExpectedTypeInfo info : expectedTypes) {
        ContainerUtil.addIfNotNull(myExpectedClasses, PsiUtil.substituteTypeParameter(info.getDefaultType(), CommonClassNames.JAVA_LANG_CLASS, 0, false));
      }

      myExpectedMemberName = calcExpectedMemberNameByParentCall(position);
    }

    @Nullable
    private static String calcExpectedMemberNameByParentCall(PsiElement position) {
      if (position.getParent() instanceof PsiJavaCodeReferenceElement) {
        PsiElement grand = position.getParent().getParent();
        if (grand instanceof PsiJavaCodeReferenceElement && ((PsiJavaCodeReferenceElement) grand).getQualifier() == position.getParent()) {
          return ((PsiJavaCodeReferenceElement) grand).getReferenceName();
        }
      }
      return null;
    }

    @Nonnull
    @Override
    public ExpectedTypeMatching weigh(@Nonnull LookupElement item) {
      if (item.getObject() instanceof PsiClass && !myConstructorPossible) {
        PsiType itemType = JavaCompletionUtil.getLookupElementType(item);
        if (itemType != null) {
          for (PsiType expectedClass : myExpectedClasses) {
            if (expectedClass.isAssignableFrom(itemType)) {
              return ExpectedTypeMatching.expected;
            }
          }
        }
        ExpectedTypeMatching byName = preferByMemberName(myExpectedMemberName, itemType);
        if (byName != ExpectedTypeMatching.normal) {
          return byName;
        }
      }

      return getExpectedTypeMatching(item, myExpectedTypes, myExpectedMemberName);
    }
  }

  private static class PreferSimilarlyEnding extends LookupElementWeigher {
    private final ExpectedTypeInfo[] myExpectedTypes;

    public PreferSimilarlyEnding(ExpectedTypeInfo[] expectedTypes) {
      super("nameEnd");
      myExpectedTypes = expectedTypes;
    }

    @Nonnull
    @Override
    public Comparable weigh(@Nonnull LookupElement element) {
      final String name = getLookupObjectName(element.getObject());
      return -getNameEndMatchingDegree(name, myExpectedTypes);
    }
  }

  private static class PreferContainingSameWords extends LookupElementWeigher {
    private final ExpectedTypeInfo[] myExpectedTypes;

    public PreferContainingSameWords(ExpectedTypeInfo[] expectedTypes) {
      super("sameWords");
      myExpectedTypes = expectedTypes;
    }

    @Nonnull
    @Override
    public Comparable weigh(@Nonnull LookupElement element) {
      final Object object = element.getObject();

      final String name = getLookupObjectName(object);
      if (name != null) {
        int max = 0;
        final List<String> wordsNoDigits = NameUtil.nameToWordsLowerCase(truncDigits(name));
        for (ExpectedTypeInfo myExpectedInfo : myExpectedTypes) {
          String expectedName = ((ExpectedTypeInfoImpl) myExpectedInfo).getExpectedName();
          if (expectedName != null) {
            final Set<String> set = new HashSet<>(NameUtil.nameToWordsLowerCase(truncDigits(expectedName)));
            set.retainAll(wordsNoDigits);
            max = Math.max(max, set.size());
          }
        }
        return -max;
      }
      return 0;
    }
  }

  private static class PreferShorter extends LookupElementWeigher {
    private final ExpectedTypeInfo[] myExpectedTypes;

    public PreferShorter(ExpectedTypeInfo[] expectedTypes) {
      super("shorter");
      myExpectedTypes = expectedTypes;
    }

    @Nonnull
    @Override
    public Comparable weigh(@Nonnull LookupElement element) {
      final Object object = element.getObject();
      final String name = getLookupObjectName(object);

      if (name != null && getNameEndMatchingDegree(name, myExpectedTypes) != 0) {
        return NameUtil.nameToWords(name).length - 1000;
      }
      return 0;
    }
  }

  private static class LiftShorterClasses extends ClassifierFactory<LookupElement> {
    final ProjectFileIndex fileIndex;
    private final PsiElement myPosition;

    public LiftShorterClasses(PsiElement position) {
      super("liftShorterClasses");
      myPosition = position;
      fileIndex = ProjectRootManager.getInstance(myPosition.getProject()).getFileIndex();
    }

    @Override
    public Classifier<LookupElement> createClassifier(Classifier<LookupElement> next) {
      return new LiftShorterItemsClassifier("liftShorterClasses", next, new LiftShorterItemsClassifier.LiftingCondition() {
        @Override
        public boolean shouldLift(LookupElement shorterElement, LookupElement longerElement) {
          Object object = shorterElement.getObject();
          if (object instanceof PsiClass && longerElement.getObject() instanceof PsiClass) {
            PsiClass psiClass = (PsiClass) object;
            PsiFile file = psiClass.getContainingFile();
            if (file != null) {
              VirtualFile vFile = file.getOriginalFile().getVirtualFile();
              if (vFile != null && fileIndex.isInSource(vFile)) {
                return true;
              }
            }
          }
          return false;
        }
      }, true);
    }
  }
}
