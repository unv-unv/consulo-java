/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.ui;

import com.intellij.java.impl.codeInsight.ExpectedTypeInfo;
import com.intellij.java.impl.codeInsight.ExpectedTypeUtil;
import com.intellij.java.impl.codeInsight.ExpectedTypesProvider;
import com.intellij.java.impl.refactoring.util.RefactoringHierarchyUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.language.editor.completion.lookup.TailType;
import consulo.language.psi.PsiManager;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.statistician.StatisticsInfo;
import consulo.language.statistician.StatisticsManager;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

/**
 * @author dsl
 */
public class TypeSelectorManagerImpl implements TypeSelectorManager {
  private SmartTypePointer myPointer;
  private PsiType myDefaultType;
  private final PsiExpression myMainOccurrence;
  private final PsiExpression[] myOccurrences;
  private final PsiType[] myTypesForMain;
  private final PsiType[] myTypesForAll;
  private final boolean myIsOneSuggestion;
  private final TypeSelector myTypeSelector;
  private final PsiElementFactory myFactory;
  private final SmartTypePointerManager mySmartTypePointerManager;
  private final ExpectedTypesProvider.ExpectedClassProvider myOccurrenceClassProvider;

  public TypeSelectorManagerImpl(Project project, PsiType type, PsiExpression[] occurrences) {
    this(project, type, occurrences, true);
  }

  public TypeSelectorManagerImpl(Project project, PsiType type, PsiExpression[] occurrences, boolean areTypesDirected) {
    myFactory = JavaPsiFacade.getInstance(project).getElementFactory();
    mySmartTypePointerManager = SmartTypePointerManager.getInstance(project);
    setDefaultType(type);
    myMainOccurrence = null;
    myOccurrences = occurrences;

    myOccurrenceClassProvider = createOccurrenceClassProvider();
    myTypesForAll = getTypesForAll(areTypesDirected);
    myTypesForMain = PsiType.EMPTY_ARRAY;

    myIsOneSuggestion = myTypesForAll.length == 1;
    if (myIsOneSuggestion) {
      myTypeSelector = new TypeSelector(myTypesForAll[0], project);
    } else {
      myTypeSelector = new TypeSelector(project);
      setTypesAndPreselect(myTypesForAll);
    }
  }

  public TypeSelectorManagerImpl(Project project, PsiType type, PsiExpression mainOccurrence, PsiExpression[] occurrences) {
    this(project, type, null, mainOccurrence, occurrences);
  }

  public TypeSelectorManagerImpl(Project project, PsiType type, PsiMethod containingMethod, PsiExpression mainOccurrence, PsiExpression[] occurrences) {
    myFactory = JavaPsiFacade.getInstance(project).getElementFactory();
    mySmartTypePointerManager = SmartTypePointerManager.getInstance(project);
    setDefaultType(type);
    myMainOccurrence = mainOccurrence;
    myOccurrences = occurrences;

    myOccurrenceClassProvider = createOccurrenceClassProvider();
    myTypesForMain = getTypesForMain();
    myTypesForAll = getTypesForAll(true);

    if (containingMethod != null) {
      if (PsiUtil.resolveClassInType(type) != null) {
        setDefaultType(checkIfTypeAccessible(type, project, containingMethod));
      }
    }

    myIsOneSuggestion = myTypesForMain.length == 1 && myTypesForAll.length == 1 &&
        myTypesForAll[0].equals(myTypesForMain[0]);
    if (myIsOneSuggestion) {
      myTypeSelector = new TypeSelector(myTypesForAll[0], project);
    } else {
      myTypeSelector = new TypeSelector(project);
    }
  }

  private PsiType checkIfTypeAccessible(PsiType type, Project project, PsiMethod containingMethod) {
    PsiClass parentClass = containingMethod.getContainingClass();
    final PsiClass typeClass = PsiUtil.resolveClassInType(type);
    if (typeClass != null) {
      if (typeClass instanceof PsiTypeParameter) {
        if (ArrayUtil.find(parentClass.getTypeParameters(), typeClass) == -1) { //unknown type parameter
          return PsiType.getJavaLangObject(PsiManager.getInstance(project), GlobalSearchScope.allScope(project));
        }
      } else if (PsiTreeUtil.isAncestor(containingMethod, typeClass, true)) { //local class type
        final int nextTypeIdx = ArrayUtil.find(myTypesForAll, type) + 1;
        if (nextTypeIdx < myTypesForAll.length) {
          return checkIfTypeAccessible(myTypesForAll[nextTypeIdx], project, containingMethod);
        }
      }
    }
    return type;
  }

  public PsiType[] getTypesForAll() {
    return myTypesForAll;
  }

  public PsiType[] getTypesForOne() {
    return myTypesForMain;
  }

  public PsiType getDefaultType() {
    if (myDefaultType.isValid()) {
      return myDefaultType;
    }
    return myPointer.getType();
  }

  public void setDefaultType(PsiType defaultType) {
    myDefaultType = defaultType;
    myPointer = mySmartTypePointerManager.createSmartTypePointer(defaultType);
  }

  private ExpectedTypesProvider.ExpectedClassProvider createOccurrenceClassProvider() {
    final Set<PsiClass> occurrenceClasses = new HashSet<PsiClass>();
    for (final PsiExpression occurrence : myOccurrences) {
      final PsiType occurrenceType = occurrence.getType();
      final PsiClass aClass = PsiUtil.resolveClassInType(occurrenceType);
      if (aClass != null) {
        occurrenceClasses.add(aClass);
      }
    }
    return new ExpectedTypeUtil.ExpectedClassesFromSetProvider(occurrenceClasses);
  }

  private PsiType[] getTypesForMain() {
    final ExpectedTypeInfo[] expectedTypes = ExpectedTypesProvider.getExpectedTypes(myMainOccurrence, false, myOccurrenceClassProvider, false);
    final ArrayList<PsiType> allowedTypes = new ArrayList<PsiType>();
    RefactoringHierarchyUtil.processSuperTypes(getDefaultType(), new RefactoringHierarchyUtil.SuperTypeVisitor() {
      @Override
      public void visitType(PsiType aType) {
        checkIfAllowed(aType);
      }

      @Override
      public void visitClass(PsiClass aClass) {
        checkIfAllowed(myFactory.createType(aClass));
      }

      private void checkIfAllowed(PsiType type) {
        if (expectedTypes.length > 0) {
          final ExpectedTypeInfo typeInfo = ExpectedTypesProvider.createInfo(type, ExpectedTypeInfo.TYPE_STRICTLY, type, TailType.NONE);
          for (ExpectedTypeInfo expectedType : expectedTypes) {
            if (expectedType.intersect(typeInfo).length != 0) {
              allowedTypes.add(type);
              break;
            }
          }
        } else {
          allowedTypes.add(type);
        }
      }
    });

    collectAllSameShapedTypes(expectedTypes, allowedTypes);

    ArrayList<PsiType> result = normalizeTypeList(allowedTypes);
    return result.toArray(PsiType.createArray(result.size()));
  }

  private static void collectAllSameShapedTypes(ExpectedTypeInfo[] expectedTypes, ArrayList<PsiType> allowedTypes) {
    for (ExpectedTypeInfo info : expectedTypes) {
      if (info.getKind() == ExpectedTypeInfo.TYPE_SAME_SHAPED) {
        allowedTypes.add(info.getDefaultType());
      }
    }
  }

  protected PsiType[] getTypesForAll(final boolean areTypesDirected) {
    final ArrayList<ExpectedTypeInfo[]> expectedTypesFromAll = new ArrayList<ExpectedTypeInfo[]>();
    for (PsiExpression occurrence : myOccurrences) {
      final ExpectedTypeInfo[] expectedTypes = ExpectedTypesProvider.getExpectedTypes(occurrence, false, myOccurrenceClassProvider, isUsedAfter());
      if (expectedTypes.length > 0) {
        expectedTypesFromAll.add(expectedTypes);
      }
    }

    final ArrayList<PsiType> allowedTypes = new ArrayList<PsiType>();
    RefactoringHierarchyUtil.processSuperTypes(getDefaultType(), new RefactoringHierarchyUtil.SuperTypeVisitor() {
      @Override
      public void visitType(PsiType aType) {
        checkIfAllowed(aType);
      }

      @Override
      public void visitClass(PsiClass aClass) {
        checkIfAllowed(myFactory.createType(aClass));
      }

      private void checkIfAllowed(PsiType type) {
        NextInfo:
        for (ExpectedTypeInfo[] expectedTypes : expectedTypesFromAll) {
          for (final ExpectedTypeInfo info : expectedTypes) {
            if (ExpectedTypeUtil.matches(type, info)) {
              continue NextInfo;
            }
          }
          return;
        }
        allowedTypes.add(type);
      }
    });

    for (ExpectedTypeInfo[] typeInfos : expectedTypesFromAll) {
      collectAllSameShapedTypes(typeInfos, allowedTypes);
    }

    final ArrayList<PsiType> result = normalizeTypeList(allowedTypes);
    if (!areTypesDirected) {
      Collections.reverse(result);
    }
    return result.toArray(PsiType.createArray(result.size()));
  }

  protected boolean isUsedAfter() {
    return false;
  }

  private ArrayList<PsiType> normalizeTypeList(final ArrayList<PsiType> typeList) {
    ArrayList<PsiType> result = new ArrayList<PsiType>();
    TypeListCreatingVisitor visitor = new TypeListCreatingVisitor(result, myFactory);
    for (PsiType psiType : typeList) {
      visitor.visitType(psiType);
    }

    final PsiType defaultType = getDefaultType();
    for (int index = 0; index < result.size(); index++) {
      PsiType psiType = result.get(index);
      if (psiType.equals(defaultType)) {
        result.remove(index);
        break;
      }
    }

    final PsiPrimitiveType unboxedType = PsiPrimitiveType.getUnboxedType(defaultType);
    if (unboxedType != null) {
      result.remove(unboxedType);
      result.add(0, unboxedType);
    }

    if (defaultType instanceof PsiPrimitiveType && myMainOccurrence != null) {
      final PsiClassType boxedType = ((PsiPrimitiveType) defaultType).getBoxedType(myMainOccurrence);
      if (boxedType != null) {
        result.remove(boxedType);
        result.add(0, boxedType);
      }
    }
    if (!TypeConversionUtil.isComposite(defaultType)) {
      result.add(0, defaultType);
    }
    return result;
  }

  @Override
  public void setAllOccurrences(boolean allOccurrences) {
    if (myIsOneSuggestion) {
      return;
    }
    setTypesAndPreselect(allOccurrences ? myTypesForAll : myTypesForMain);
  }

  private void setTypesAndPreselect(PsiType[] types) {
    myTypeSelector.setTypes(types);

    Map<String, PsiType> map = new HashMap<String, PsiType>();
    for (final PsiType type : types) {
      map.put(serialize(type), type);
    }

    for (StatisticsInfo info : StatisticsManager.getInstance().getAllValues(getStatsKey())) {
      final PsiType candidate = map.get(info.getValue());
      if (candidate != null && StatisticsManager.getInstance().getUseCount(info) > 0) {
        myTypeSelector.selectType(candidate);
        return;
      }
    }
  }

  @Override
  public boolean isSuggestedType(final String fqName) {
    for (PsiType type : myTypesForAll) {
      if (type.getCanonicalText().equals(fqName)) {
        return true;
      }
    }

    for (PsiType type : myTypesForMain) {
      if (type.getCanonicalText().equals(fqName)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public void typeSelected(@Nonnull PsiType type) {
    typeSelected(type, getDefaultType());
  }

  public static void typeSelected(@Nonnull final PsiType type, @Nullable final PsiType defaultType) {
    if (defaultType == null) {
      return;
    }
    StatisticsManager.getInstance().incUseCount(new StatisticsInfo(getStatsKey(defaultType), serialize(type)));
  }

  private String getStatsKey() {
    final PsiType defaultType = getDefaultType();
    if (defaultType == null) {
      return "IntroduceVariable##";
    }
    return getStatsKey(defaultType);
  }

  private static String getStatsKey(final PsiType defaultType) {
    return "IntroduceVariable##" + serialize(defaultType);
  }

  private static String serialize(@Nonnull PsiType type) {
    if (PsiUtil.resolveClassInType(type) instanceof PsiTypeParameter) {
      return type.getCanonicalText();
    }
    return TypeConversionUtil.erasure(type).getCanonicalText();
  }

  @Override
  public TypeSelector getTypeSelector() {
    return myTypeSelector;
  }

}
