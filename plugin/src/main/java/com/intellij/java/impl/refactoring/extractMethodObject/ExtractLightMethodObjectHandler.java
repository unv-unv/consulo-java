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
package com.intellij.java.impl.refactoring.extractMethodObject;

import com.intellij.java.analysis.impl.refactoring.extractMethod.InputVariables;
import com.intellij.java.analysis.impl.refactoring.util.VariableData;
import com.intellij.java.impl.codeInsight.CodeInsightUtil;
import com.intellij.java.impl.refactoring.extractMethod.AbstractExtractDialog;
import com.intellij.java.impl.refactoring.extractMethod.PrepareFailedException;
import com.intellij.java.impl.refactoring.util.RefactoringUtil;
import com.intellij.java.language.impl.psi.controlFlow.*;
import com.intellij.java.language.psi.PsiElementFactory;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.util.VisibilityUtil;
import consulo.document.util.TextRange;
import consulo.language.codeStyle.CodeStyleManager;
import consulo.language.psi.*;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.usage.UsageInfo;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;

import jakarta.annotation.Nullable;
import java.util.List;

public class ExtractLightMethodObjectHandler {
  private static final Logger LOG = Logger.getInstance(ExtractLightMethodObjectHandler.class);

  public static class ExtractedData {
    private final String myGeneratedCallText;
    private final PsiClass myGeneratedInnerClass;
    private final PsiElement myAnchor;

    public ExtractedData(String generatedCallText, PsiClass generatedInnerClass, PsiElement anchor) {
      myGeneratedCallText = generatedCallText;
      myGeneratedInnerClass = generatedInnerClass;
      myAnchor = anchor;
    }

    public PsiElement getAnchor() {
      return myAnchor;
    }

    public String getGeneratedCallText() {
      return myGeneratedCallText;
    }

    public PsiClass getGeneratedInnerClass() {
      return myGeneratedInnerClass;
    }
  }

  @Nullable
  public static ExtractedData extractLightMethodObject(final Project project,
                                                       @Nullable PsiElement originalContext,
                                                       @Nonnull final PsiCodeFragment fragment,
                                                       final String methodName) throws PrepareFailedException {
    final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
    PsiElement[] elements = completeToStatementArray(fragment, elementFactory);
    if (elements == null) {
      elements = CodeInsightUtil.findStatementsInRange(fragment, 0, fragment.getTextLength());
    }
    if (elements.length == 0) {
      return null;
    }

    if (originalContext == null) {
      return null;
    }

    PsiFile file = originalContext.getContainingFile();

    final PsiFile copy = PsiFileFactory.getInstance(project).createFileFromText(file.getName(), file.getFileType(), file.getText(), file.getModificationStamp(), false);

    if (originalContext instanceof PsiKeyword && PsiModifier.PRIVATE.equals(originalContext.getText())) {
      final PsiNameIdentifierOwner identifierOwner = PsiTreeUtil.getParentOfType(originalContext, PsiNameIdentifierOwner.class);
      if (identifierOwner != null) {
        final PsiElement identifier = identifierOwner.getNameIdentifier();
        if (identifier != null) {
          originalContext = identifier;
        }
      }
    }

    final TextRange range = originalContext.getTextRange();
    PsiElement originalAnchor = CodeInsightUtil.findElementInRange(copy, range.getStartOffset(), range.getEndOffset(), originalContext.getClass());
    if (originalAnchor == null) {
      final PsiElement elementAt = copy.findElementAt(range.getStartOffset());
      if (elementAt != null && elementAt.getClass() == originalContext.getClass()) {
        originalAnchor = PsiTreeUtil.skipSiblingsForward(elementAt, PsiWhiteSpace.class);
      }
    }

    final PsiClass containingClass = PsiTreeUtil.getParentOfType(originalAnchor, PsiClass.class, false);
    if (containingClass == null) {
      return null;
    }

    PsiElement anchor = RefactoringUtil.getParentStatement(originalAnchor, false);
    if (anchor == null) {
      if (PsiTreeUtil.getParentOfType(originalAnchor, PsiCodeBlock.class) != null) {
        anchor = originalAnchor;
      }
    }

    final PsiElement container;
    if (anchor == null) {
      container = ((PsiClassInitializer) containingClass.add(elementFactory.createClassInitializer())).getBody();
      anchor = container.getLastChild();
    } else {
      container = anchor.getParent();
    }

    final PsiElement firstElementCopy = container.addRangeBefore(elements[0], elements[elements.length - 1], anchor);
    final PsiElement[] elementsCopy = CodeInsightUtil.findStatementsInRange(copy, firstElementCopy.getTextRange().getStartOffset(), anchor.getTextRange().getStartOffset());
    if (elementsCopy.length == 0) {
      return null;
    }
    if (elementsCopy[elementsCopy.length - 1] instanceof PsiExpressionStatement) {
      final PsiExpression expr = ((PsiExpressionStatement) elementsCopy[elementsCopy.length - 1]).getExpression();
      if (!(expr instanceof PsiAssignmentExpression)) {
        PsiType expressionType = GenericsUtil.getVariableTypeByExpressionType(expr.getType());
        if (expressionType instanceof PsiDisjunctionType) {
          expressionType = ((PsiDisjunctionType) expressionType).getLeastUpperBound();
        }
        if (isValidVariableType(expressionType)) {
          final String uniqueResultName = JavaCodeStyleManager.getInstance(project).suggestUniqueVariableName("result", elementsCopy[0], true);
          final String statementText = expressionType.getCanonicalText() + " " + uniqueResultName + " = " + expr.getText() + ";";
          elementsCopy[elementsCopy.length - 1] = elementsCopy[elementsCopy.length - 1].replace(elementFactory.createStatementFromText(statementText, elementsCopy[elementsCopy.length -
              1]));
        }
      }
    }

    LOG.assertTrue(elementsCopy[0].getParent() == container, "element: " + elementsCopy[0].getText() + "; container: " + container.getText());
    final int startOffsetInContainer = elementsCopy[0].getStartOffsetInParent();

    final ControlFlow controlFlow;
    try {
      controlFlow = ControlFlowFactory.getInstance(project).getControlFlow(container, LocalsOrMyInstanceFieldsControlFlowPolicy.getInstance(), ControlFlowOptions.NO_CONST_EVALUATE);
    } catch (AnalysisCanceledException e) {
      return null;
    }

    List<PsiVariable> variables = ControlFlowUtil.getUsedVariables(controlFlow, controlFlow.getStartOffset(elementsCopy[0]), controlFlow.getEndOffset(elementsCopy[elementsCopy.length - 1]));

    variables = ContainerUtil.filter(variables, variable ->
    {
      PsiElement variableScope = PsiUtil.getVariableCodeBlock(variable, null);
      return variableScope != null && PsiTreeUtil.isAncestor(variableScope, elementsCopy[elementsCopy.length - 1], true);
    });

    final String outputVariables = StringUtil.join(variables, variable -> "\"variable: \" + " + variable.getName(), " +");
    PsiStatement outStatement = elementFactory.createStatementFromText("System.out.println(" + outputVariables + ");", anchor);
    outStatement = (PsiStatement) container.addAfter(outStatement, elementsCopy[elementsCopy.length - 1]);

    copy.accept(new JavaRecursiveElementWalkingVisitor() {
      private void makePublic(PsiMember method) {
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
          VisibilityUtil.setVisibility(method.getModifierList(), PsiModifier.PUBLIC);
        }
      }

      @Override
      public void visitMethod(PsiMethod method) {
        super.visitMethod(method);
        makePublic(method);
      }

      @Override
      public void visitField(PsiField field) {
        super.visitField(field);
        makePublic(field);
      }
    });

    final ExtractMethodObjectProcessor extractMethodObjectProcessor = new ExtractMethodObjectProcessor(project, null, elementsCopy, "") {
      @Override
      protected AbstractExtractDialog createExtractMethodObjectDialog(MyExtractMethodProcessor processor) {
        return new LightExtractMethodObjectDialog(this, methodName);
      }

      @Override
      protected boolean isFoldingApplicable() {
        return false;
      }
    };
    extractMethodObjectProcessor.getExtractProcessor().setShowErrorDialogs(false);

    final ExtractMethodObjectProcessor.MyExtractMethodProcessor extractProcessor = extractMethodObjectProcessor.getExtractProcessor();
    if (extractProcessor.prepare()) {
      if (extractProcessor.showDialog()) {
        try {
          extractProcessor.doExtract();
          final UsageInfo[] usages = extractMethodObjectProcessor.findUsages();
          extractMethodObjectProcessor.performRefactoring(usages);
          extractMethodObjectProcessor.runChangeSignature();
        } catch (IncorrectOperationException e) {
          LOG.error(e);
        }
        if (extractMethodObjectProcessor.isCreateInnerClass()) {
          extractMethodObjectProcessor.changeInstanceAccess(project);
        }
        final PsiElement method = extractMethodObjectProcessor.getMethod();
        LOG.assertTrue(method != null);
        method.delete();
      }
    } else {
      return null;
    }

    final int startOffset = startOffsetInContainer + container.getTextRange().getStartOffset();
    final String generatedCall = copy.getText().substring(startOffset, outStatement.getTextOffset());
    return new ExtractedData(generatedCall, (PsiClass) CodeStyleManager.getInstance(project).reformat(extractMethodObjectProcessor.getInnerClass()), originalAnchor);
  }

  @Nullable
  private static PsiElement[] completeToStatementArray(PsiCodeFragment fragment, PsiElementFactory elementFactory) {
    PsiExpression expression = CodeInsightUtil.findExpressionInRange(fragment, 0, fragment.getTextLength());
    if (expression != null) {
      String completeExpressionText = null;
      if (expression instanceof PsiArrayInitializerExpression) {
        final PsiExpression[] initializers = ((PsiArrayInitializerExpression) expression).getInitializers();
        if (initializers.length > 0) {
          final PsiType type = initializers[0].getType();
          if (type != null) {
            completeExpressionText = "new " + type.getCanonicalText() + "[]" + expression.getText();
          }
        }
      } else {
        completeExpressionText = expression.getText();
      }

      if (completeExpressionText != null) {
        return new PsiElement[]{elementFactory.createStatementFromText(completeExpressionText + ";", expression)};
      }
    }
    return null;
  }

  private static boolean isValidVariableType(PsiType type) {
    if (type instanceof PsiClassType || type instanceof PsiArrayType || type instanceof PsiPrimitiveType && !PsiType.VOID.equals(type)) {
      return true;
    }
    return false;
  }

  private static class LightExtractMethodObjectDialog implements AbstractExtractDialog {
    private final ExtractMethodObjectProcessor myProcessor;
    private final String myMethodName;

    public LightExtractMethodObjectDialog(ExtractMethodObjectProcessor processor, String methodName) {
      myProcessor = processor;
      myMethodName = methodName;
    }

    @Override
    public String getChosenMethodName() {
      return myMethodName;
    }

    @Override
    public VariableData[] getChosenParameters() {
      final InputVariables inputVariables = myProcessor.getExtractProcessor().getInputVariables();
      return inputVariables.getInputVariables().toArray(new VariableData[inputVariables.getInputVariables().size()]);
    }

    @Override
    public String getVisibility() {
      return PsiModifier.PUBLIC;
    }

    @Override
    public boolean isMakeStatic() {
      return false;
    }

    @Override
    public boolean isChainedConstructor() {
      return false;
    }

    @Override
    public PsiType getReturnType() {
      return null;
    }

    @Override
    public void show() {
    }

    @Override
    public boolean isOK() {
      return true;
    }
  }
}
