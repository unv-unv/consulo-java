// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.impl.codeInsight.daemon.impl.quickfix;

import com.intellij.java.analysis.impl.codeInsight.daemon.impl.analysis.JavaHighlightUtil;
import com.intellij.java.analysis.impl.find.findUsages.JavaMethodFindUsagesOptions;
import com.intellij.java.impl.ide.util.SuperMethodWarningUtil;
import com.intellij.java.impl.refactoring.changeSignature.ChangeSignatureProcessor;
import com.intellij.java.impl.refactoring.changeSignature.JavaChangeSignatureDialog;
import com.intellij.java.impl.refactoring.changeSignature.ParameterInfoImpl;
import com.intellij.java.impl.refactoring.util.RefactoringUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.codeStyle.VariableKind;
import com.intellij.java.language.psi.impl.source.resolve.graphInference.PsiPolyExpressionUtil;
import com.intellij.java.language.psi.util.PsiTypesUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.application.ApplicationManager;
import consulo.application.progress.ProgressManager;
import consulo.application.util.function.Processor;
import consulo.codeEditor.Editor;
import consulo.component.ProcessCanceledException;
import consulo.container.PluginException;
import consulo.container.plugin.PluginDescriptor;
import consulo.container.plugin.PluginManager;
import consulo.find.FindManager;
import consulo.find.FindUsagesHandler;
import consulo.ide.impl.idea.find.findUsages.FindUsagesManager;
import consulo.ide.impl.idea.find.impl.FindManagerImpl;
import consulo.java.analysis.impl.JavaQuickFixBundle;
import consulo.java.impl.codeInsight.JavaTargetElementUtilEx;
import consulo.language.editor.FileModificationService;
import consulo.language.editor.intention.SyntheticIntentionAction;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.rename.SuggestedNameInfo;
import consulo.language.editor.util.LanguageUndoUtil;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.undoRedo.CommandProcessor;
import consulo.usage.UsageInfo;
import consulo.util.collection.ArrayUtil;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.annotations.NonNls;

import java.util.*;
import java.util.function.Consumer;

public class ChangeMethodSignatureFromUsageFix implements SyntheticIntentionAction {
  final PsiMethod myTargetMethod;
  final PsiExpression[] myExpressions;
  final PsiSubstitutor mySubstitutor;
  final PsiElement myContext;
  private final boolean myChangeAllUsages;
  private final int myMinUsagesNumberToShowDialog;
  ParameterInfoImpl[] myNewParametersInfo;
  private String myShortName;
  private static final Logger LOG = Logger.getInstance(ChangeMethodSignatureFromUsageFix.class);

  public ChangeMethodSignatureFromUsageFix(@Nonnull PsiMethod targetMethod,
                                           @Nonnull PsiExpression[] expressions,
                                           @Nonnull PsiSubstitutor substitutor,
                                           @Nonnull PsiElement context,
                                           boolean changeAllUsages, int minUsagesNumberToShowDialog) {
    myTargetMethod = targetMethod;
    myExpressions = expressions;
    mySubstitutor = substitutor;
    myContext = context;
    myChangeAllUsages = changeAllUsages;
    myMinUsagesNumberToShowDialog = minUsagesNumberToShowDialog;
  }

  @Override
  @Nonnull
  public String getText() {
    final String shortText = myShortName;
    if (shortText != null) {
      return shortText;
    }
    return JavaQuickFixBundle.message("change.method.signature.from.usage.text",
        JavaHighlightUtil.formatMethod(myTargetMethod),
        myTargetMethod.getName(),
        formatTypesList(myNewParametersInfo, myContext));
  }

  private String getShortText(final StringBuilder buf,
                              final HashSet<? extends ParameterInfoImpl> newParams,
                              final HashSet<? extends ParameterInfoImpl> removedParams,
                              final HashSet<? extends ParameterInfoImpl> changedParams) {
    final String targetMethodName = myTargetMethod.getName();
    if (myTargetMethod.getContainingClass().findMethodsByName(targetMethodName, true).length == 1) {
      if (newParams.size() == 1) {
        final ParameterInfoImpl p = newParams.iterator().next();
        return JavaQuickFixBundle
            .message("add.parameter.from.usage.text", p.getTypeText(), ArrayUtil.find(myNewParametersInfo, p) + 1, targetMethodName);
      }
      if (removedParams.size() == 1) {
        final ParameterInfoImpl p = removedParams.iterator().next();
        return JavaQuickFixBundle.message("remove.parameter.from.usage.text", p.getOldIndex() + 1, targetMethodName);
      }
      if (changedParams.size() == 1) {
        final ParameterInfoImpl p = changedParams.iterator().next();
        return JavaQuickFixBundle.message("change.parameter.from.usage.text", p.getOldIndex() + 1, targetMethodName,
            Objects.requireNonNull(myTargetMethod.getParameterList().getParameter(p.getOldIndex())).getType().getPresentableText(),
            p.getTypeText());
      }
    }
    return "<html> Change signature of " + targetMethodName + "(" + buf + ")</html>";
  }

  @Nullable
  private static String formatTypesList(ParameterInfoImpl[] infos, PsiElement context) {
    if (infos == null) {
      return null;
    }
    StringBuilder result = new StringBuilder();
    try {
      for (ParameterInfoImpl info : infos) {
        PsiType type = info.createType(context);
        if (type == null) {
          return null;
        }
        if (result.length() != 0) {
          result.append(", ");
        }
        result.append(type.getPresentableText());
      }
      return result.toString();
    } catch (IncorrectOperationException e) {
      return null;
    }
  }

  @Override
  public boolean isAvailable(@Nonnull Project project, Editor editor, PsiFile file) {
    if (!myTargetMethod.isValid() || myTargetMethod.getContainingClass() == null) {
      return false;
    }
    for (PsiExpression expression : myExpressions) {
      if (!expression.isValid()) {
        return false;
      }
    }
    if (!mySubstitutor.isValid()) {
      return false;
    }

    final StringBuilder buf = new StringBuilder();
    final HashSet<ParameterInfoImpl> newParams = new HashSet<>();
    final HashSet<ParameterInfoImpl> removedParams = new HashSet<>();
    final HashSet<ParameterInfoImpl> changedParams = new HashSet<>();
    myNewParametersInfo = getNewParametersInfo(myExpressions, myTargetMethod, mySubstitutor, buf, newParams, removedParams, changedParams);
    if (myNewParametersInfo == null || formatTypesList(myNewParametersInfo, myContext) == null) {
      return false;
    }
    myShortName = getShortText(buf, newParams, removedParams, changedParams);
    return !isMethodSignatureExists();
  }

  public boolean isMethodSignatureExists() {
    PsiClass target = myTargetMethod.getContainingClass();
    LOG.assertTrue(target != null);
    PsiMethod[] methods = target.findMethodsByName(myTargetMethod.getName(), false);
    for (PsiMethod method : methods) {
      if (PsiUtil.isApplicable(method, PsiSubstitutor.EMPTY, myExpressions)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void invoke(@Nonnull final Project project, Editor editor, final PsiFile file) {
    if (!FileModificationService.getInstance().prepareFileForWrite(file)) {
      return;
    }

    final PsiMethod method = SuperMethodWarningUtil.checkSuperMethod(myTargetMethod, RefactoringLocalize.toRefactor().get());
    if (method == null) {
      return;
    }
    myNewParametersInfo = getNewParametersInfo(myExpressions, myTargetMethod, mySubstitutor);

    final List<ParameterInfoImpl> parameterInfos =
        performChange(project, editor, file, method, myMinUsagesNumberToShowDialog, myNewParametersInfo, myChangeAllUsages, false, null);
    if (parameterInfos != null) {
      myNewParametersInfo = parameterInfos.toArray(new ParameterInfoImpl[0]);
    }
  }

  static List<ParameterInfoImpl> performChange(@Nonnull Project project,
                                               final Editor editor,
                                               final PsiFile file,
                                               @Nonnull PsiMethod method,
                                               final int minUsagesNumber,
                                               final ParameterInfoImpl[] newParametersInfo,
                                               final boolean changeAllUsages,
                                               final boolean allowDelegation,
                                               @Nullable final Consumer<? super List<ParameterInfoImpl>> callback) {
    if (!FileModificationService.getInstance().prepareFileForWrite(method.getContainingFile())) {
      return null;
    }
    final FindUsagesManager findUsagesManager = ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();
    final FindUsagesHandler handler = findUsagesManager.getFindUsagesHandler(method, false);
    if (handler == null) {
      return null;//on failure or cancel (e.g. cancel of super methods dialog)
    }

    final JavaMethodFindUsagesOptions options = new JavaMethodFindUsagesOptions(project);
    options.isImplementingMethods = true;
    options.isOverridingMethods = true;
    options.isUsages = true;
    options.isSearchForTextOccurrences = false;
    final int[] usagesFound = new int[1];
    Runnable runnable = () -> {
      Processor<UsageInfo> processor = t -> ++usagesFound[0] < minUsagesNumber;

      handler.processElementUsages(method, processor, options);
    };
    String progressTitle = JavaQuickFixBundle.message("searching.for.usages.progress.title");
    if (!ProgressManager.getInstance().runProcessWithProgressSynchronously(runnable, progressTitle, true, project)) {
      return null;
    }

    if (ApplicationManager.getApplication().isUnitTestMode() || usagesFound[0] < minUsagesNumber) {
      ChangeSignatureProcessor processor = new ChangeSignatureProcessor(
          project,
          method,
          false, null,
          method.getName(),
          method.getReturnType(),
          newParametersInfo) {
        @Override
        protected UsageInfo[] findUsages() {
          return changeAllUsages ? super.findUsages() : UsageInfo.EMPTY_ARRAY;
        }

        @Override
        protected void performRefactoring(UsageInfo[] usages) {
          CommandProcessor.getInstance().setCurrentCommandName(getCommandName());
          super.performRefactoring(usages);
          if (callback != null) {
            callback.accept(Arrays.asList(newParametersInfo));
          }
        }
      };
      processor.run();
      ApplicationManager.getApplication().runWriteAction(() -> LanguageUndoUtil.markPsiFileForUndo(file));
      return Arrays.asList(newParametersInfo);
    } else {
      final List<ParameterInfoImpl> parameterInfos = newParametersInfo != null
          ? new ArrayList<>(Arrays.asList(newParametersInfo))
          : new ArrayList<>();
      final PsiReferenceExpression refExpr = JavaTargetElementUtilEx.findReferenceExpression(editor);
      JavaChangeSignatureDialog dialog = JavaChangeSignatureDialog.createAndPreselectNew(project, method, parameterInfos, allowDelegation, refExpr, callback);
      dialog.setParameterInfos(parameterInfos);
      dialog.show();
      return dialog.isOK() ? dialog.getParameters() : null;
    }
  }

  public static String getNewParameterNameByOldIndex(int oldIndex, final ParameterInfoImpl[] parametersInfo) {
    if (parametersInfo == null) {
      return null;
    }
    for (ParameterInfoImpl info : parametersInfo) {
      if (info.oldParameterIndex == oldIndex) {
        return info.getName();
      }
    }
    return null;
  }

  protected ParameterInfoImpl[] getNewParametersInfo(PsiExpression[] expressions,
                                                     PsiMethod targetMethod,
                                                     PsiSubstitutor substitutor) {
    return getNewParametersInfo(expressions, targetMethod, substitutor, new StringBuilder(), new HashSet<>(),
        new HashSet<>(),
        new HashSet<>());
  }

  private ParameterInfoImpl[] getNewParametersInfo(PsiExpression[] expressions,
                                                   PsiMethod targetMethod,
                                                   PsiSubstitutor substitutor,
                                                   final StringBuilder buf,
                                                   final HashSet<? super ParameterInfoImpl> newParams,
                                                   final HashSet<? super ParameterInfoImpl> removedParams,
                                                   final HashSet<? super ParameterInfoImpl> changedParams) {
    PsiParameter[] parameters = targetMethod.getParameterList().getParameters();
    List<ParameterInfoImpl> result = new ArrayList<>();
    if (expressions.length < parameters.length) {
      // find which parameters to remove
      int ei = 0;
      int pi = 0;

      while (ei < expressions.length && pi < parameters.length) {
        PsiExpression expression = expressions[ei];
        PsiParameter parameter = parameters[pi];
        PsiType paramType = substitutor.substitute(parameter.getType());
        if (buf.length() > 0) {
          buf.append(", ");
        }
        final PsiType parameterType = PsiUtil.convertAnonymousToBaseType(paramType);
        final String presentableText = escapePresentableType(parameterType);
        final ParameterInfoImpl parameterInfo = ParameterInfoImpl.create(pi).withName(parameter.getName()).withType(parameter.getType());
        if (TypeConversionUtil.areTypesAssignmentCompatible(paramType, expression)) {
          buf.append(presentableText);
          result.add(parameterInfo);
          pi++;
          ei++;
        } else {
          buf.append("<s>").append(presentableText).append("</s>");
          removedParams.add(parameterInfo);
          pi++;
        }
      }
      if (result.size() != expressions.length) {
        return null;
      }
      for (int i = pi; i < parameters.length; i++) {
        if (buf.length() > 0) {
          buf.append(", ");
        }
        buf.append("<s>").append(escapePresentableType(parameters[i].getType())).append("</s>");
        final ParameterInfoImpl parameterInfo = ParameterInfoImpl.create(pi)
            .withName(parameters[i].getName())
            .withType(parameters[i].getType());
        removedParams.add(parameterInfo);
      }
    } else if (expressions.length > parameters.length) {
      if (!findNewParamsPlace(expressions, targetMethod, substitutor, buf, newParams, parameters, result)) {
        return null;
      }
    } else {
      //parameter type changed
      for (int i = 0; i < parameters.length; i++) {
        if (buf.length() > 0) {
          buf.append(", ");
        }
        PsiParameter parameter = parameters[i];
        PsiExpression expression = expressions[i];
        PsiType bareParamType = parameter.getType();
        if (!bareParamType.isValid()) {
          try {
            PsiUtil.ensureValidType(bareParamType);
          } catch (ProcessCanceledException e) {
            throw e;
          } catch (Throwable e) {
            PluginDescriptor plugin = PluginManager.getPlugin(parameter.getClass());

            throw new PluginException(parameter.getClass() + "; valid=" + parameter.isValid() + "; method.valid=" + targetMethod.isValid(), e, plugin.getPluginId());
          }
        }
        PsiType paramType = substitutor.substitute(bareParamType);
        PsiUtil.ensureValidType(paramType);
        final String presentableText = escapePresentableType(paramType);
        if (TypeConversionUtil.areTypesAssignmentCompatible(paramType, expression)) {
          result.add(ParameterInfoImpl.create(i).withName(parameter.getName()).withType(paramType));
          buf.append(presentableText);
        } else {
          if (PsiPolyExpressionUtil.isPolyExpression(expression)) {
            return null;
          }
          PsiType exprType = RefactoringUtil.getTypeByExpression(expression);
          if (exprType == null || PsiType.VOID.equals(exprType)) {
            return null;
          }
          if (exprType instanceof PsiDisjunctionType) {
            exprType = ((PsiDisjunctionType) exprType).getLeastUpperBound();
          }
          if (!PsiTypesUtil.allTypeParametersResolved(myTargetMethod, exprType)) {
            return null;
          }
          final ParameterInfoImpl changedParameterInfo = ParameterInfoImpl.create(i).withName(parameter.getName()).withType(exprType);
          result.add(changedParameterInfo);
          changedParams.add(changedParameterInfo);
          buf.append("<s>").append(presentableText).append("</s> <b>").append(escapePresentableType(exprType)).append("</b>");
        }
      }
      // do not perform silly refactorings
      boolean isSilly = true;
      for (int i = 0; i < result.size(); i++) {
        PsiParameter parameter = parameters[i];
        PsiType paramType = substitutor.substitute(parameter.getType());
        ParameterInfoImpl parameterInfo = result.get(i);
        String typeText = parameterInfo.getTypeText();
        if (!paramType.equalsToText(typeText) && !paramType.getPresentableText().equals(typeText)) {
          isSilly = false;
          break;
        }
      }
      if (isSilly) {
        return null;
      }
    }
    return result.toArray(new ParameterInfoImpl[0]);
  }

  @Nonnull
  protected static String escapePresentableType(@Nonnull PsiType exprType) {
    return StringUtil.escapeXmlEntities(exprType.getPresentableText());
  }

  protected boolean findNewParamsPlace(PsiExpression[] expressions,
                                       PsiMethod targetMethod,
                                       PsiSubstitutor substitutor,
                                       StringBuilder buf,
                                       HashSet<? super ParameterInfoImpl> newParams,
                                       PsiParameter[] parameters,
                                       List<? super ParameterInfoImpl> result) {
    // find which parameters to introduce and where
    Set<String> existingNames = new HashSet<>();
    for (PsiParameter parameter : parameters) {
      existingNames.add(parameter.getName());
    }
    int ei = 0;
    int pi = 0;
    PsiParameter varargParam = targetMethod.isVarArgs() ? parameters[parameters.length - 1] : null;
    while (ei < expressions.length || pi < parameters.length) {
      if (buf.length() > 0) {
        buf.append(", ");
      }
      PsiExpression expression = ei < expressions.length ? expressions[ei] : null;
      PsiParameter parameter = pi < parameters.length ? parameters[pi] : null;
      PsiType paramType = parameter == null ? null : substitutor.substitute(parameter.getType());
      boolean parameterAssignable = paramType != null && (expression == null || TypeConversionUtil
          .areTypesAssignmentCompatible(paramType, expression));
      if (parameterAssignable) {
        final PsiType type = parameter.getType();
        result.add(ParameterInfoImpl.create(pi).withName(parameter.getName()).withType(type));
        buf.append(escapePresentableType(type));
        pi++;
        ei++;
      } else if (isArgumentInVarargPosition(expressions, ei, varargParam, substitutor)) {
        if (pi == parameters.length - 1) {
          assert varargParam != null;
          final PsiType type = varargParam.getType();
          result.add(ParameterInfoImpl.create(pi).withName(varargParam.getName()).withType(type));
          buf.append(escapePresentableType(type));
        }
        pi++;
        ei++;
      } else if (expression != null) {
        if (varargParam != null && pi >= parameters.length) {
          return false;
        }
        if (PsiPolyExpressionUtil.isPolyExpression(expression)) {
          return false;
        }
        PsiType exprType = RefactoringUtil.getTypeByExpression(expression);
        if (exprType == null || PsiType.VOID.equals(exprType)) {
          return false;
        }
        if (exprType instanceof PsiDisjunctionType) {
          exprType = ((PsiDisjunctionType) exprType).getLeastUpperBound();
        }
        if (!PsiTypesUtil.allTypeParametersResolved(myTargetMethod, exprType)) {
          return false;
        }
        JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(expression.getProject());
        String name = suggestUniqueParameterName(codeStyleManager, expression, exprType, existingNames);
        final ParameterInfoImpl newParameterInfo = ParameterInfoImpl.createNew()
            .withName(name)
            .withType(exprType)
            .withDefaultValue(expression.getText().replace('\n', ' '));
        result.add(newParameterInfo);
        newParams.add(newParameterInfo);
        buf.append("<b>").append(escapePresentableType(exprType)).append("</b>");
        ei++;
      }
    }
    if (result.size() != expressions.length && varargParam == null) {
      return false;
    }
    return true;
  }

  static boolean isArgumentInVarargPosition(PsiExpression[] expressions, int ei, PsiParameter varargParam, PsiSubstitutor substitutor) {
    if (varargParam == null) {
      return false;
    }
    final PsiExpression expression = expressions[ei];
    if (expression == null || TypeConversionUtil.areTypesAssignmentCompatible(substitutor.substitute(((PsiEllipsisType) varargParam.getType()).getComponentType()), expression)) {
      final int lastExprIdx = expressions.length - 1;
      if (ei == lastExprIdx) {
        return true;
      }
      return expressions[lastExprIdx].getType() != PsiType.NULL;
    }
    return false;
  }

  static String suggestUniqueParameterName(JavaCodeStyleManager codeStyleManager,
                                           PsiExpression expression,
                                           PsiType exprType,
                                           Set<? super String> existingNames) {
    SuggestedNameInfo nameInfo = codeStyleManager.suggestVariableName(VariableKind.PARAMETER, null, expression, exprType);
    @NonNls String[] names = nameInfo.names;
    if (expression instanceof PsiReferenceExpression) {
      final PsiElement resolve = ((PsiReferenceExpression) expression).resolve();
      if (resolve instanceof PsiVariable) {
        final VariableKind variableKind = codeStyleManager.getVariableKind((PsiVariable) resolve);
        final String propertyName = codeStyleManager.variableNameToPropertyName(((PsiVariable) resolve).getName(), variableKind);
        final String parameterName = codeStyleManager.propertyNameToVariableName(propertyName, VariableKind.PARAMETER);
        names = ArrayUtil.mergeArrays(new String[]{parameterName}, names);
      }
    }
    if (names.length == 0) {
      names = new String[]{"param"};
    }
    int suffix = 0;
    while (true) {
      for (String name : names) {
        String suggested = name + (suffix == 0 ? "" : String.valueOf(suffix));
        if (existingNames.add(suggested)) {
          return suggested;
        }
      }
      suffix++;
    }
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }
}
