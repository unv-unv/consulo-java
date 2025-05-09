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

import com.intellij.java.impl.codeInsight.generation.GenerateMembersUtil;
import com.intellij.java.impl.refactoring.introduceField.InplaceIntroduceFieldPopup;
import com.intellij.java.impl.refactoring.introduceVariable.IntroduceVariableBase;
import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.LanguageLevel;
import com.intellij.java.language.patterns.PsiJavaPatterns;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.codeStyle.VariableKind;
import com.intellij.java.language.psi.util.PropertyUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.AllIcons;
import consulo.application.util.matcher.PrefixMatcher;
import consulo.externalService.statistic.FeatureUsageTracker;
import consulo.language.Language;
import consulo.language.editor.completion.*;
import consulo.language.editor.completion.lookup.*;
import consulo.language.editor.refactoring.rename.SuggestedNameInfo;
import consulo.language.editor.template.TemplateManager;
import consulo.language.pattern.ElementPattern;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiReference;
import consulo.language.psi.PsiUtilCore;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

import static com.intellij.java.language.patterns.PsiJavaPatterns.psiClass;
import static com.intellij.java.language.patterns.PsiJavaPatterns.psiField;
import static consulo.language.pattern.PlatformPatterns.psiElement;
import static consulo.language.pattern.StandardPatterns.or;

/**
 * @author peter
 */
@ExtensionImpl(id = "javaMemberName", order = "before javaOverride")
public class JavaMemberNameCompletionContributor extends CompletionContributor {
  public static final ElementPattern<PsiElement> INSIDE_TYPE_PARAMS_PATTERN = psiElement().
    afterLeaf(psiElement().withText("?").andOr(
      psiElement().afterLeaf("<", ","),
      psiElement().afterSiblingSkipping(psiElement().whitespaceCommentEmptyOrError(), psiElement(PsiAnnotation.class))));

  static final int MAX_SCOPE_SIZE_TO_SEARCH_UNRESOLVED = 50000;

  @RequiredReadAction
  @Override
  public void fillCompletionVariants(@Nonnull CompletionParameters parameters, @Nonnull CompletionResultSet result) {
    if (parameters.getCompletionType() != CompletionType.BASIC && parameters.getCompletionType() != CompletionType.SMART) {
      return;
    }

    Project project = parameters.getPosition().getProject();
    if (parameters.getInvocationCount() == 0 && TemplateManager.getInstance(project).getTemplateState(parameters.getEditor()) != null) {
      return;
    }

    PsiElement position = parameters.getPosition();
    Set<LookupElement> lookupSet = new HashSet<>();
    if (psiElement(PsiIdentifier.class).andNot(INSIDE_TYPE_PARAMS_PATTERN).withParent(
      or(psiElement(PsiLocalVariable.class), psiElement(PsiParameter.class))).accepts(position)) {
      completeLocalVariableName(lookupSet, result.getPrefixMatcher(), (PsiVariable)parameters.getPosition().getParent(),
                                parameters.getInvocationCount() >= 1);
      for (LookupElement item : lookupSet) {
        if (item instanceof LookupItem) {
          ((LookupItem<?>)item).setAutoCompletionPolicy(AutoCompletionPolicy.GIVE_CHANCE_TO_OVERWRITE);
        }
      }
    }

    if (psiElement(PsiIdentifier.class).withParent(PsiField.class).andNot(INSIDE_TYPE_PARAMS_PATTERN).accepts(position)) {
      PsiField variable = (PsiField)parameters.getPosition().getParent();
      completeMethodName(lookupSet, variable, result.getPrefixMatcher());
      completeFieldName(lookupSet, variable, result.getPrefixMatcher(), parameters.getInvocationCount() >= 1);
    }

    if (psiElement(PsiIdentifier.class).withParent(PsiRecordComponent.class).andNot(INSIDE_TYPE_PARAMS_PATTERN).accepts(position)) {
      PsiRecordComponent component = (PsiRecordComponent)parameters.getPosition().getParent();
      completeComponentName(lookupSet, component, result.getPrefixMatcher());
    }

    if (PsiJavaPatterns.psiJavaElement().nameIdentifierOf(PsiJavaPatterns.psiMethod().withParent(PsiClass.class)).accepts(position)) {
      completeMethodName(lookupSet, parameters.getPosition().getParent(), result.getPrefixMatcher());
    }

    for (LookupElement item : lookupSet) {
      result.addElement(item);
    }
  }

  private static void completeComponentName(Set<LookupElement> set, @Nonnull PsiRecordComponent var, PrefixMatcher matcher) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed("editing.completion.variable.name");

    Project project = var.getProject();
    JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);

    SuggestedNameInfo suggestedNameInfo = codeStyleManager.suggestVariableName(VariableKind.PARAMETER, null, null, var.getType());
    String[] suggestedNames = suggestedNameInfo.names;
    addLookupItems(set, suggestedNameInfo, matcher, project, suggestedNames);
  }

  private static void completeLocalVariableName(Set<LookupElement> set, PrefixMatcher matcher, PsiVariable var, boolean includeOverlapped) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed("editing.completion.variable.name");
    Project project = var.getProject();
    final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
    final VariableKind variableKind = codeStyleManager.getVariableKind(var);

    String propertyName = null;
    if (variableKind == VariableKind.PARAMETER) {
      final PsiMethod method = PsiTreeUtil.getParentOfType(var, PsiMethod.class);
      if (method != null) {
        propertyName = PropertyUtil.getPropertyName(method);
      }
      if (method != null && method.getName().startsWith("with")) {
        propertyName = StringUtil.decapitalize(method.getName().substring(4));
      }
    }

    final PsiType type = var.getType();
    SuggestedNameInfo suggestedNameInfo = codeStyleManager.suggestVariableName(variableKind, propertyName, null, type, StringUtil.isEmpty(matcher.getPrefix()));
    suggestedNameInfo = codeStyleManager.suggestUniqueVariableName(suggestedNameInfo, var, false);
    final String[] suggestedNames = suggestedNameInfo.names;
    addLookupItems(set, suggestedNameInfo, matcher, project, suggestedNames);
    if (!hasStartMatches(set, matcher)) {
      if (type.equalsToText(CommonClassNames.JAVA_LANG_OBJECT) && matcher.prefixMatches("object")) {
        set.add(withInsertHandler(suggestedNameInfo, LookupElementBuilder.create("object")));
      }
      if (type.equalsToText(CommonClassNames.JAVA_LANG_STRING) && matcher.prefixMatches("string")) {
        set.add(withInsertHandler(suggestedNameInfo, LookupElementBuilder.create("string")));
      }
    }

    if (!hasStartMatches(set, matcher) && includeOverlapped) {
      addLookupItems(set, null, matcher, project, getOverlappedNameVersions(matcher.getPrefix(), suggestedNames, ""));
    }
    PsiElement parent = PsiTreeUtil.getParentOfType(var, PsiCodeBlock.class);
    if (parent == null) {
      parent = PsiTreeUtil.getParentOfType(var, PsiMethod.class, PsiLambdaExpression.class);
    }
    addLookupItems(set, suggestedNameInfo, matcher, project, getUnresolvedReferences(parent, false));
    if (var instanceof PsiParameter && parent instanceof PsiMethod) {
      addSuggestionsInspiredByFieldNames(set, matcher, var, project, codeStyleManager);
    }

    PsiExpression initializer = var.getInitializer();
    if (initializer != null) {
      SuggestedNameInfo initializerSuggestions = IntroduceVariableBase.getSuggestedName(type, initializer);
      addLookupItems(set, initializerSuggestions, matcher, project, initializerSuggestions.names);
    }
  }

  private static boolean hasStartMatches(PrefixMatcher matcher, Set<String> set) {
    for (String s : set) {
      if (matcher.isStartMatch(s)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasStartMatches(Set<LookupElement> set, PrefixMatcher matcher) {
    for (LookupElement lookupElement : set) {
      if (matcher.isStartMatch(lookupElement)) {
        return true;
      }
    }
    return false;
  }

  private static void addSuggestionsInspiredByFieldNames(Set<LookupElement> set, PrefixMatcher matcher, PsiVariable var, Project project, JavaCodeStyleManager codeStyleManager) {
    PsiClass psiClass = PsiTreeUtil.getParentOfType(var, PsiClass.class);
    if (psiClass == null) {
      return;
    }

    for (PsiField field : psiClass.getFields()) {
      if (field.getType().isAssignableFrom(var.getType())) {
        String prop = codeStyleManager.variableNameToPropertyName(field.getName(), VariableKind.FIELD);
        addLookupItems(set, null, matcher, project, codeStyleManager.propertyNameToVariableName(prop, VariableKind.PARAMETER));
      }
    }
  }

  private static String[] getOverlappedNameVersions(final String prefix, final String[] suggestedNames, String suffix) {
    final List<String> newSuggestions = new ArrayList<>();
    int longestOverlap = 0;

    for (String suggestedName : suggestedNames) {
      if (suggestedName.length() < 3) {
        continue;
      }

      if (suggestedName.toUpperCase().startsWith(prefix.toUpperCase())) {
        newSuggestions.add(suggestedName);
        longestOverlap = prefix.length();
      }

      suggestedName = String.valueOf(Character.toUpperCase(suggestedName.charAt(0))) + suggestedName.substring(1);
      final int overlap = getOverlap(suggestedName, prefix);

      if (overlap < longestOverlap) {
        continue;
      }

      if (overlap > longestOverlap) {
        newSuggestions.clear();
        longestOverlap = overlap;
      }

      String suggestion = prefix.substring(0, prefix.length() - overlap) + suggestedName;

      final int lastIndexOfSuffix = suggestion.lastIndexOf(suffix);
      if (lastIndexOfSuffix >= 0 && suffix.length() < suggestion.length() - lastIndexOfSuffix) {
        suggestion = suggestion.substring(0, lastIndexOfSuffix) + suffix;
      }

      if (!newSuggestions.contains(suggestion)) {
        newSuggestions.add(suggestion);
      }
    }
    return ArrayUtil.toStringArray(newSuggestions);
  }

  private static int getOverlap(final String propertyName, final String prefix) {
    int overlap = 0;
    int propertyNameLen = propertyName.length();
    int prefixLen = prefix.length();
    for (int j = 1; j < prefixLen && j < propertyNameLen; j++) {
      if (prefix.substring(prefixLen - j).equals(propertyName.substring(0, j))) {
        overlap = j;
      }
    }
    return overlap;
  }

  @RequiredReadAction
  private static String[] getUnresolvedReferences(final PsiElement parentOfType, final boolean referenceOnMethod) {
    if (parentOfType != null && parentOfType.getTextLength() > MAX_SCOPE_SIZE_TO_SEARCH_UNRESOLVED) {
      return ArrayUtil.EMPTY_STRING_ARRAY;
    }
    final Set<String> unresolvedRefs = new LinkedHashSet<>();

    if (parentOfType != null) {
      parentOfType.accept(new JavaRecursiveElementWalkingVisitor() {
        @Override
        @RequiredReadAction
        public void visitReferenceExpression(PsiReferenceExpression reference) {
          final PsiElement parent = reference.getParent();
          if (parent instanceof PsiReference) {
            return;
          }
          if (referenceOnMethod && parent instanceof PsiMethodCallExpression && reference == ((PsiMethodCallExpression) parent).getMethodExpression()) {
            if (reference.resolve() == null) {
              ContainerUtil.addIfNotNull(unresolvedRefs, reference.getReferenceName());
            }
          } else if (!referenceOnMethod && !(parent instanceof PsiMethodCallExpression) && reference.resolve() == null) {
            ContainerUtil.addIfNotNull(unresolvedRefs, reference.getReferenceName());
          }
        }
      });
    }
    return ArrayUtil.toStringArray(unresolvedRefs);
  }

  private static void completeFieldName(Set<LookupElement> set, PsiField var, final PrefixMatcher matcher, boolean includeOverlapped) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed("editing.completion.variable.name");

    Project project = var.getProject();
    JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
    final VariableKind variableKind = JavaCodeStyleManager.getInstance(project).getVariableKind(var);

    final String prefix = matcher.getPrefix();
    if (PsiType.VOID.equals(var.getType()) || psiField().inClass(psiClass().isInterface().andNot(psiClass().isAnnotationType())).accepts(var)) {
      completeVariableNameForRefactoring(project, set, matcher, var.getType(), variableKind, includeOverlapped, true);
      return;
    }

    SuggestedNameInfo suggestedNameInfo = codeStyleManager.suggestVariableName(variableKind, null, null, var.getType());
    final String[] suggestedNames = suggestedNameInfo.names;
    addLookupItems(set, suggestedNameInfo, matcher, project, suggestedNames);

    if (!hasStartMatches(set, matcher) && includeOverlapped) {
      // use suggested names as suffixes
      final String requiredSuffix = codeStyleManager.getSuffixByVariableKind(variableKind);
      if (variableKind != VariableKind.STATIC_FINAL_FIELD) {
        for (int i = 0; i < suggestedNames.length; i++) {
          suggestedNames[i] = codeStyleManager.variableNameToPropertyName(suggestedNames[i], variableKind);
        }
      }


      addLookupItems(set, null, matcher, project, getOverlappedNameVersions(prefix, suggestedNames, requiredSuffix));
    }

    addLookupItems(set, suggestedNameInfo, matcher, project, getUnresolvedReferences(var.getParent(), false));

    PsiExpression initializer = var.getInitializer();
    PsiClass containingClass = var.getContainingClass();
    if (initializer != null && containingClass != null) {
      SuggestedNameInfo initializerSuggestions = InplaceIntroduceFieldPopup.
          suggestFieldName(var.getType(), null, initializer, var.hasModifierProperty(PsiModifier.STATIC), containingClass);
      addLookupItems(set, initializerSuggestions, matcher, project, initializerSuggestions.names);
    }
  }

  public static void completeVariableNameForRefactoring(Project project,
                                                        Set<LookupElement> set,
                                                        PrefixMatcher matcher,
                                                        PsiType varType,
                                                        VariableKind varKind,
                                                        final boolean includeOverlapped,
                                                        final boolean methodPrefix) {
    JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
    SuggestedNameInfo suggestedNameInfo = codeStyleManager.suggestVariableName(varKind, null, null, varType);
    final String[] strings = completeVariableNameForRefactoring(codeStyleManager, matcher, varType, varKind, suggestedNameInfo, includeOverlapped, methodPrefix);
    addLookupItems(set, suggestedNameInfo, matcher, project, strings);
  }

  public static String[] completeVariableNameForRefactoring(JavaCodeStyleManager codeStyleManager,
                                                            final PrefixMatcher matcher,
                                                            @Nullable final PsiType varType,
                                                            final VariableKind varKind,
                                                            SuggestedNameInfo suggestedNameInfo,
                                                            final boolean includeOverlapped,
                                                            final boolean methodPrefix) {
    Set<String> result = new LinkedHashSet<>();
    final String[] suggestedNames = suggestedNameInfo.names;
    for (final String suggestedName : suggestedNames) {
      if (matcher.prefixMatches(suggestedName)) {
        result.add(suggestedName);
      }
    }

    if (!hasStartMatches(matcher, result) && !PsiType.VOID.equals(varType) && includeOverlapped) {
      // use suggested names as suffixes
      final String requiredSuffix = codeStyleManager.getSuffixByVariableKind(varKind);
      final String prefix = matcher.getPrefix();
      if (varKind != VariableKind.STATIC_FINAL_FIELD || methodPrefix) {
        for (int i = 0; i < suggestedNames.length; i++) {
          suggestedNames[i] = codeStyleManager.variableNameToPropertyName(suggestedNames[i], varKind);
        }
      }

      ContainerUtil.addAll(result, getOverlappedNameVersions(prefix, suggestedNames, requiredSuffix));


    }
    return ArrayUtil.toStringArray(result);
  }

  private static void completeMethodName(Set<LookupElement> set, PsiElement element, final PrefixMatcher matcher) {
    if (element instanceof PsiMethod) {
      final PsiMethod method = (PsiMethod) element;
      if (method.isConstructor()) {
        final PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
          final String name = containingClass.getName();
          if (StringUtil.isNotEmpty(name)) {
            addLookupItems(set, null, matcher, element.getProject(), name);
          }
        }
        return;
      }
    }

    PsiClass ourClassParent = PsiTreeUtil.getParentOfType(element, PsiClass.class);
    if (ourClassParent == null) {
      return;
    }

    if (ourClassParent.isAnnotationType() && matcher.prefixMatches(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)) {
      set.add(LookupElementBuilder.create(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME).withIcon(AllIcons.Nodes.Method).withTailText("()").withInsertHandler(ParenthesesInsertHandler
          .NO_PARAMETERS));
    }

    addLookupItems(set, null, matcher, element.getProject(), getUnresolvedReferences(ourClassParent, true));

    addLookupItems(set, null, matcher, element.getProject(), getPropertiesHandlersNames(ourClassParent, ((PsiModifierListOwner) element).hasModifierProperty(PsiModifier.STATIC), PsiUtil
        .getTypeByPsiElement(element), element));
  }

  private static String[] getPropertiesHandlersNames(final PsiClass psiClass, final boolean staticContext, final PsiType varType, final PsiElement element) {
    final List<String> propertyHandlers = new ArrayList<>();

    for (final PsiField field : psiClass.getFields()) {
      if (field == element) {
        continue;
      }
      if (StringUtil.isEmpty(field.getName())) {
        continue;
      }

      PsiUtilCore.ensureValid(field);
      PsiType fieldType = field.getType();
      PsiUtil.ensureValidType(fieldType);

      final PsiModifierList modifierList = field.getModifierList();
      if (staticContext && (modifierList != null && !modifierList.hasModifierProperty(PsiModifier.STATIC))) {
        continue;
      }

      if (fieldType.equals(varType)) {
        final String getterName = PropertyUtil.suggestGetterName(field);
        if ((psiClass.findMethodsByName(getterName, true).length == 0 || psiClass.findMethodBySignature(GenerateMembersUtil.generateGetterPrototype(field), true) == null)) {
          propertyHandlers.add(getterName);
        }
      }

      if (PsiType.VOID.equals(varType)) {
        final String setterName = PropertyUtil.suggestSetterName(field);
        if ((psiClass.findMethodsByName(setterName, true).length == 0 || psiClass.findMethodBySignature(GenerateMembersUtil.generateSetterPrototype(field), true) == null)) {
          propertyHandlers.add(setterName);
        }
      }
    }

    return ArrayUtil.toStringArray(propertyHandlers);
  }

  private static void addLookupItems(Set<LookupElement> lookupElements, @Nullable final SuggestedNameInfo callback, PrefixMatcher matcher, Project project, String... strings) {
    outer:
    for (int i = 0; i < strings.length; i++) {
      String name = strings[i];
      if (!matcher.prefixMatches(name) || !PsiNameHelper.getInstance(project).isIdentifier(name, LanguageLevel.HIGHEST)) {
        continue;
      }

      for (LookupElement lookupElement : lookupElements) {
        if (lookupElement.getAllLookupStrings().contains(name)) {
          continue outer;
        }
      }

      LookupElement element = PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).withAutoCompletionPolicy(AutoCompletionPolicy.GIVE_CHANCE_TO_OVERWRITE), -i);
      if (callback != null) {
        element = withInsertHandler(callback, element);
      }
      lookupElements.add(element);
    }
  }

  private static LookupElementDecorator<LookupElement> withInsertHandler(final SuggestedNameInfo callback, LookupElement element) {
    return LookupElementDecorator.withInsertHandler(element, (context, item) -> {
      TailType tailType = LookupItem.getDefaultTailType(context.getCompletionChar());
      if (tailType != null) {
        context.setAddCompletionChar(false);
        tailType.processTail(context.getEditor(), context.getTailOffset());
      }
      callback.nameChosen(item.getLookupString());
    });
  }

  @Nonnull
  @Override
  public Language getLanguage() {
    return JavaLanguage.INSTANCE;
  }
}
