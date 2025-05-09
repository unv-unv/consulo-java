/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.java.analysis.impl.codeInspection;

import com.intellij.java.analysis.codeInspection.BaseJavaBatchLocalInspectionTool;
import com.intellij.java.analysis.impl.codeInsight.daemon.impl.analysis.HighlightControlFlowUtil;
import com.intellij.java.language.LanguageLevel;
import com.intellij.java.language.codeInsight.AnnotationUtil;
import com.intellij.java.language.impl.codeInsight.ChangeContextUtil;
import com.intellij.java.language.impl.psi.controlFlow.AnalysisCanceledException;
import com.intellij.java.language.impl.psi.controlFlow.ControlFlow;
import com.intellij.java.language.impl.psi.controlFlow.ControlFlowUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.util.InheritanceUtil;
import com.intellij.java.language.psi.util.PsiTypesUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.RedundantCastUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.component.util.text.UniqueNameGenerator;
import consulo.document.util.TextRange;
import consulo.language.editor.inspection.*;
import consulo.language.editor.inspection.localize.InspectionLocalize;
import consulo.language.editor.intention.HighPriorityAction;
import consulo.language.editor.rawHighlight.HighlightDisplayLevel;
import consulo.language.psi.PsiComment;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Comparing;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import org.jetbrains.annotations.Nls;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * User: anna
 */
@ExtensionImpl
public class AnonymousCanBeLambdaInspection extends BaseJavaBatchLocalInspectionTool<AnonymousCanBeLambdaInspectionState> {
  public static final Logger LOG = Logger.getInstance(AnonymousCanBeLambdaInspection.class);

  @Nls
  @Nonnull
  @Override
  public String getGroupDisplayName() {
    return InspectionLocalize.groupNamesLanguageLevelSpecificIssuesAndMigrationAids().get();
  }

  @Nls
  @Nonnull
  @Override
  public String getDisplayName() {
    return "Anonymous type can be replaced with lambda";
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Nonnull
  @Override
  public String getShortName() {
    return "Convert2Lambda";
  }

  @Nonnull
  @Override
  public HighlightDisplayLevel getDefaultLevel() {
    return HighlightDisplayLevel.WARNING;
  }

  @Nonnull
  @Override
  public AnonymousCanBeLambdaInspectionState createStateProvider() {
    return new AnonymousCanBeLambdaInspectionState();
  }

  @Nonnull
  @Override
  public PsiElementVisitor buildVisitorImpl(@Nonnull final ProblemsHolder holder,
                                            boolean isOnTheFly,
                                            LocalInspectionToolSession session,
                                            AnonymousCanBeLambdaInspectionState state) {
    return new JavaElementVisitor() {
      @Override
      public void visitAnonymousClass(final PsiAnonymousClass aClass) {
        super.visitAnonymousClass(aClass);
        final PsiElement parent = aClass.getParent();
        final PsiElement lambdaContext = parent != null ? parent.getParent() : null;
        if (lambdaContext != null &&
            (LambdaUtil.isValidLambdaContext(lambdaContext) || !(lambdaContext instanceof PsiExpressionStatement)) &&
            canBeConvertedToLambda(aClass, false, isOnTheFly || state.reportNotAnnotatedInterfaces, Collections.emptySet())) {
          final PsiElement lBrace = aClass.getLBrace();
          LOG.assertTrue(lBrace != null);
          final TextRange rangeInElement = new TextRange(0, aClass.getStartOffsetInParent() + lBrace.getStartOffsetInParent());
          ProblemHighlightType problemHighlightType = ProblemHighlightType.LIKE_UNUSED_SYMBOL;
          if (isOnTheFly && !state.reportNotAnnotatedInterfaces) {
            final PsiClass baseClass = aClass.getBaseClassType().resolve();
            LOG.assertTrue(baseClass != null);
            if (!AnnotationUtil.isAnnotated(baseClass, CommonClassNames.JAVA_LANG_FUNCTIONAL_INTERFACE, false, false)) {
              problemHighlightType = ProblemHighlightType.INFORMATION;
            }
          }
          holder.registerProblem(parent, "Anonymous #ref #loc can be replaced with lambda", problemHighlightType, rangeInElement, new ReplaceWithLambdaFix());
        }
      }
    };
  }

  static boolean hasRuntimeAnnotations(PsiMethod method, @Nonnull Set<String> runtimeAnnotationsToIgnore) {
    PsiAnnotation[] annotations = method.getModifierList().getAnnotations();
    for (PsiAnnotation annotation : annotations) {
      PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
      PsiElement target = ref != null ? ref.resolve() : null;
      if (target instanceof PsiClass) {
        if (runtimeAnnotationsToIgnore.contains(((PsiClass) target).getQualifiedName())) {
          continue;
        }
        final PsiAnnotation retentionAnno = AnnotationUtil.findAnnotation((PsiClass) target, Retention.class.getName());
        if (retentionAnno != null) {
          PsiAnnotationMemberValue value = retentionAnno.findAttributeValue("value");
          if (value instanceof PsiReferenceExpression) {
            final PsiElement resolved = ((PsiReferenceExpression) value).resolve();
            if (resolved instanceof PsiField && RetentionPolicy.RUNTIME.name().equals(((PsiField) resolved).getName())) {
              final PsiClass containingClass = ((PsiField) resolved).getContainingClass();
              if (containingClass != null && RetentionPolicy.class.getName().equals(containingClass.getQualifiedName())) {
                return true;
              }
            }
          }
        }
      }
    }
    return false;
  }

  public static boolean hasForbiddenRefsInsideBody(PsiMethod method, PsiAnonymousClass aClass) {
    final ForbiddenRefsChecker checker = new ForbiddenRefsChecker(method, aClass);
    final PsiCodeBlock body = method.getBody();
    LOG.assertTrue(body != null);
    body.accept(checker);
    return checker.hasForbiddenRefs();
  }

  private static PsiType getInferredType(PsiAnonymousClass aClass, PsiMethod method) {
    final PsiExpression expression = (PsiExpression) aClass.getParent();
    final PsiType psiType = PsiTypesUtil.getExpectedTypeByParent(expression);
    if (psiType != null) {
      return psiType;
    }

    PsiExpression topExpr = expression;
    while (topExpr.getParent() instanceof PsiParenthesizedExpression) {
      topExpr = (PsiExpression) topExpr.getParent();
    }

    final PsiCall call = LambdaUtil.treeWalkUp(topExpr);
    if (call != null && call.resolveMethod() != null) {
      final int offsetInTopCall = aClass.getTextRange().getStartOffset() - call.getTextRange().getStartOffset();
      PsiCall copyCall = LambdaUtil.copyTopLevelCall(call);
      if (copyCall == null) {
        return null;
      }
      final PsiAnonymousClass classArg = PsiTreeUtil.getParentOfType(copyCall.findElementAt(offsetInTopCall), PsiAnonymousClass.class);
      if (classArg != null) {
        PsiExpression lambda = JavaPsiFacade.getElementFactory(aClass.getProject()).createExpressionFromText(ReplaceWithLambdaFix.composeLambdaText(method), expression);
        lambda = (PsiExpression) classArg.getParent().replace(lambda);
        ((PsiLambdaExpression) lambda).getBody().replace(method.getBody());
        final PsiType interfaceType;
        if (copyCall.resolveMethod() == null) {
          return PsiType.NULL;
        } else {
          interfaceType = ((PsiLambdaExpression) lambda).getFunctionalInterfaceType();
        }

        return interfaceType;
      }
    }

    return PsiType.NULL;
  }

  public static boolean canBeConvertedToLambda(PsiAnonymousClass aClass, boolean acceptParameterizedFunctionTypes, @Nonnull Set<String> ignoredRuntimeAnnotations) {
    return canBeConvertedToLambda(aClass, acceptParameterizedFunctionTypes, true, ignoredRuntimeAnnotations);
  }

  public static boolean isLambdaForm(PsiAnonymousClass aClass, Set<String> ignoredRuntimeAnnotations) {
    PsiMethod[] methods = aClass.getMethods();
    if (methods.length != 1) {
      return false;
    }
    PsiMethod method = methods[0];
    return aClass.getFields().length == 0 &&
        aClass.getInnerClasses().length == 0 &&
        aClass.getInitializers().length == 0 &&
        method.getBody() != null &&
        method.getDocComment() == null &&
        !hasRuntimeAnnotations(method, ignoredRuntimeAnnotations) &&
        !method.hasModifierProperty(PsiModifier.SYNCHRONIZED) &&
        !hasForbiddenRefsInsideBody(method, aClass);
  }

  public static boolean canBeConvertedToLambda(PsiAnonymousClass aClass,
                                               boolean acceptParameterizedFunctionTypes,
                                               boolean reportNotAnnotatedInterfaces,
                                               @Nonnull Set<String> ignoredRuntimeAnnotations) {
    if (PsiUtil.getLanguageLevel(aClass).isAtLeast(LanguageLevel.JDK_1_8)) {
      final PsiClassType baseClassType = aClass.getBaseClassType();
      final PsiClassType.ClassResolveResult resolveResult = baseClassType.resolveGenerics();
      final PsiClass baseClass = resolveResult.getElement();
      if (baseClass == null || !reportNotAnnotatedInterfaces && !AnnotationUtil.isAnnotated(baseClass, CommonClassNames.JAVA_LANG_FUNCTIONAL_INTERFACE, false, false)) {
        return false;
      }
      final PsiMethod interfaceMethod = LambdaUtil.getFunctionalInterfaceMethod(resolveResult);
      if (interfaceMethod != null && (acceptParameterizedFunctionTypes || !interfaceMethod.hasTypeParameters())) {
        if (isLambdaForm(aClass, ignoredRuntimeAnnotations)) {
          final PsiMethod method = aClass.getMethods()[0];
          return getInferredType(aClass, method) != null;
        }
      }
    }
    return false;
  }

  public static PsiExpression replaceAnonymousWithLambda(@Nonnull PsiElement anonymousClass, PsiType expectedType) {
    PsiNewExpression newArrayExpression = (PsiNewExpression) JavaPsiFacade.getElementFactory(anonymousClass.getProject()).createExpressionFromText("new " + expectedType.getCanonicalText() +
        "[]{" + anonymousClass.getText() + "}", anonymousClass);
    PsiArrayInitializerExpression initializer = newArrayExpression.getArrayInitializer();
    LOG.assertTrue(initializer != null);
    return replacePsiElementWithLambda(initializer.getInitializers()[0], true, false);
  }

  public static PsiExpression replacePsiElementWithLambda(@Nonnull PsiElement element, final boolean ignoreEqualsMethod, boolean forceIgnoreTypeCast) {
    if (!(element instanceof PsiNewExpression)) {
      return null;
    }

    final PsiNewExpression newExpression = (PsiNewExpression) element;
    final PsiAnonymousClass anonymousClass = newExpression.getAnonymousClass();

    if (anonymousClass == null) {
      return null;
    }

    final PsiMethod method;
    if (ignoreEqualsMethod) {
      final List<PsiMethod> methods = ContainerUtil.filter(anonymousClass.getMethods(), method1 -> !"equals".equals(method1.getName()));
      method = methods.get(0);
    } else {
      method = anonymousClass.getMethods()[0];
    }
    if (method == null || method.getBody() == null) {
      return null;
    }

    return generateLambdaByMethod(anonymousClass, method, lambda -> (PsiLambdaExpression) newExpression.replace(lambda), forceIgnoreTypeCast);
  }

  /**
   * Try convert given method of given anonymous class into lambda and replace given element.
   *
   * @param anonymousClass      physical anonymous class containing method
   * @param method              physical method to convert with non-empty body
   * @param replacer            an operator which actually inserts a lambda into the file (possibly removing anonymous class)
   *                            and returns an inserted physical lambda
   * @param forceIgnoreTypeCast if false, type cast might be added if necessary
   * @return newly-generated lambda expression (possibly with typecast)
   */
  @Nonnull
  static PsiExpression generateLambdaByMethod(PsiAnonymousClass anonymousClass, PsiMethod method, UnaryOperator<PsiLambdaExpression> replacer, boolean forceIgnoreTypeCast) {
    ChangeContextUtil.encodeContextInfo(anonymousClass, true);
    final String canonicalText = anonymousClass.getBaseClassType().getCanonicalText();

    final PsiCodeBlock body = method.getBody();
    LOG.assertTrue(body != null);

    final Collection<PsiComment> comments = collectCommentsOutsideMethodBody(anonymousClass, body);
    final Project project = anonymousClass.getProject();
    final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);

    final String withoutTypesDeclared = ReplaceWithLambdaFix.composeLambdaText(method);

    PsiLambdaExpression lambdaExpression = (PsiLambdaExpression) elementFactory.createExpressionFromText(withoutTypesDeclared, anonymousClass);

    PsiElement lambdaBody = lambdaExpression.getBody();
    LOG.assertTrue(lambdaBody != null);
    lambdaBody.replace(body);
    lambdaExpression = replacer.apply(lambdaExpression);

    final Set<PsiVariable> variables = new HashSet<>();
    final Set<String> usedLocalNames = new HashSet<>();

    collectLocalVariablesDefinedInsideLambda(lambdaExpression, variables, usedLocalNames);

    ReplaceWithLambdaFix.giveUniqueNames(project, elementFactory, lambdaExpression, usedLocalNames, variables.toArray(new PsiVariable[variables.size()]));

    final PsiExpression singleExpr = RedundantLambdaCodeBlockInspection.isCodeBlockRedundant(lambdaExpression.getBody());
    if (singleExpr != null) {
      lambdaExpression.getBody().replace(singleExpr);
    }
    ChangeContextUtil.decodeContextInfo(lambdaExpression, null, null);
    restoreComments(comments, lambdaExpression);

    final JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
    if (forceIgnoreTypeCast) {
      return (PsiExpression) javaCodeStyleManager.shortenClassReferences(lambdaExpression);
    }

    PsiTypeCastExpression typeCast = (PsiTypeCastExpression) elementFactory.createExpressionFromText("(" + canonicalText + ")" + withoutTypesDeclared, lambdaExpression);
    final PsiExpression typeCastOperand = typeCast.getOperand();
    LOG.assertTrue(typeCastOperand instanceof PsiLambdaExpression);
    final PsiElement fromText = ((PsiLambdaExpression) typeCastOperand).getBody();
    LOG.assertTrue(fromText != null);
    lambdaBody = lambdaExpression.getBody();
    LOG.assertTrue(lambdaBody != null);
    fromText.replace(lambdaBody);
    ((PsiLambdaExpression) typeCastOperand).getParameterList().replace(lambdaExpression.getParameterList());
    typeCast = (PsiTypeCastExpression) lambdaExpression.replace(typeCast);
    if (RedundantCastUtil.isCastRedundant(typeCast)) {
      final PsiExpression operand = typeCast.getOperand();
      LOG.assertTrue(operand != null);
      return (PsiExpression) typeCast.replace(operand);
    }
    return (PsiExpression) javaCodeStyleManager.shortenClassReferences(typeCast);
  }

  @Nonnull
  static Collection<PsiComment> collectCommentsOutsideMethodBody(PsiAnonymousClass anonymousClass, PsiCodeBlock body) {
    final Collection<PsiComment> psiComments = PsiTreeUtil.findChildrenOfType(anonymousClass, PsiComment.class);
    psiComments.removeIf(comment -> PsiTreeUtil.isAncestor(body, comment, false));
    return ContainerUtil.map(psiComments, (comment) -> (PsiComment) comment.copy());
  }

  private static void collectLocalVariablesDefinedInsideLambda(PsiLambdaExpression lambdaExpression, final Set<PsiVariable> variables, Set<String> namesOfVariablesInTheBlock) {
    PsiElement block = PsiUtil.getTopLevelEnclosingCodeBlock(lambdaExpression, null);
    if (block == null) {
      block = lambdaExpression;
    }

    block.accept(new JavaRecursiveElementWalkingVisitor() {
      @Override
      public void visitVariable(PsiVariable variable) {
        super.visitVariable(variable);
        if (!(variable instanceof PsiField)) {
          variables.add(variable);
        }
      }
    });

    final PsiResolveHelper helper = PsiResolveHelper.getInstance(lambdaExpression.getProject());
    for (Iterator<PsiVariable> iterator = variables.iterator(); iterator.hasNext(); ) {
      PsiVariable local = iterator.next();
      final String localName = local.getName();
      if (localName == null ||
          shadowingResolve(localName, lambdaExpression, helper) ||
          !PsiTreeUtil.isAncestor(lambdaExpression, local, false)) {
        iterator.remove();
        namesOfVariablesInTheBlock.add(localName);
      }
    }
  }

  private static boolean shadowingResolve(String localName, PsiLambdaExpression lambdaExpression, PsiResolveHelper helper) {
    final PsiVariable variable = helper.resolveReferencedVariable(localName, lambdaExpression);
    return variable == null || variable instanceof PsiField;
  }

  private static class ReplaceWithLambdaFix implements LocalQuickFix, HighPriorityAction {
    @Nonnull
    @Override
    public String getFamilyName() {
      return "Replace with lambda";
    }

    @Override
    public void applyFix(@Nonnull Project project, @Nonnull ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      if (element != null) {
        replacePsiElementWithLambda(element, false, false);
      }
    }

    private static void giveUniqueNames(Project project, final PsiElementFactory elementFactory, PsiElement body, Set<String> usedLocalNames, PsiVariable[] parameters) {
      final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
      final Map<PsiVariable, String> names = new HashMap<>();
      for (PsiVariable parameter : parameters) {
        String parameterName = parameter.getName();
        String uniqueVariableName = UniqueNameGenerator.generateUniqueName(codeStyleManager.suggestUniqueVariableName(parameterName, parameter.getParent(), false), usedLocalNames);
        if (!Comparing.equal(parameterName, uniqueVariableName)) {
          names.put(parameter, uniqueVariableName);
        }
      }

      if (names.isEmpty()) {
        return;
      }

      final Map<PsiElement, PsiElement> replacements = new LinkedHashMap<>();
      body.accept(new JavaRecursiveElementWalkingVisitor() {
        @Override
        public void visitVariable(PsiVariable variable) {
          super.visitVariable(variable);
          final String newName = names.get(variable);
          if (newName != null) {
            replacements.put(variable.getNameIdentifier(), elementFactory.createIdentifier(newName));
          }
        }

        @Override
        public void visitReferenceExpression(PsiReferenceExpression expression) {
          super.visitReferenceExpression(expression);
          final PsiElement resolve = expression.resolve();
          if (resolve instanceof PsiVariable) {
            final String newName = names.get(resolve);
            if (newName != null) {
              replacements.put(expression, elementFactory.createExpressionFromText(newName, expression));
            }
          }
        }
      });

      for (PsiElement psiElement : replacements.keySet()) {
        psiElement.replace(replacements.get(psiElement));
      }
    }

    private static String composeLambdaText(PsiMethod method) {
      final StringBuilder buf = new StringBuilder();
      final PsiParameter[] parameters = method.getParameterList().getParameters();
      if (parameters.length != 1) {
        buf.append("(");
      }
      buf.append(StringUtil.join(parameters, ReplaceWithLambdaFix::composeParameter, ","));
      if (parameters.length != 1) {
        buf.append(")");
      }
      buf.append("-> {}");
      return buf.toString();
    }

    private static String composeParameter(PsiParameter parameter) {
      String parameterName = parameter.getName();
      if (parameterName == null) {
        parameterName = "";
      }
      return parameterName;
    }
  }

  public static boolean functionalInterfaceMethodReferenced(PsiMethod psiMethod, PsiAnonymousClass anonymClass, PsiCallExpression callExpression) {
    if (psiMethod != null && !psiMethod.hasModifierProperty(PsiModifier.STATIC)) {
      final PsiClass containingClass = psiMethod.getContainingClass();
      if (containingClass != null && CommonClassNames.JAVA_LANG_OBJECT.equals(containingClass.getQualifiedName())) {
        return false;
      }

      if (callExpression instanceof PsiMethodCallExpression && ((PsiMethodCallExpression) callExpression).getMethodExpression().isQualified()) {
        return false;
      }

      if (InheritanceUtil.isInheritorOrSelf(anonymClass, containingClass, true) && !InheritanceUtil.hasEnclosingInstanceInScope(containingClass, anonymClass.getParent(), true, true)) {
        return true;
      }
    }
    return false;
  }

  public static void restoreComments(Collection<PsiComment> comments, PsiElement lambda) {
    PsiElement anchor = PsiTreeUtil.getParentOfType(lambda, PsiStatement.class, PsiField.class);
    if (anchor == null) {
      anchor = lambda;
    }
    for (PsiComment comment : comments) {
      anchor.getParent().addBefore(comment, anchor);
    }
  }

  private static class ForbiddenRefsChecker extends JavaRecursiveElementWalkingVisitor {
    private boolean myBodyContainsForbiddenRefs;

    private final PsiMethod myMethod;
    private final PsiAnonymousClass myAnonymClass;

    public ForbiddenRefsChecker(PsiMethod method, PsiAnonymousClass aClass) {
      myMethod = method;
      myAnonymClass = aClass;
    }

    @Override
    public void visitMethodCallExpression(PsiMethodCallExpression methodCallExpression) {
      if (myBodyContainsForbiddenRefs) {
        return;
      }

      super.visitMethodCallExpression(methodCallExpression);
      final PsiMethod psiMethod = methodCallExpression.resolveMethod();
      if (psiMethod == myMethod ||
          functionalInterfaceMethodReferenced(psiMethod, myAnonymClass, methodCallExpression) ||
          psiMethod != null &&
              !methodCallExpression.getMethodExpression().isQualified() &&
              "getClass".equals(psiMethod.getName()) &&
              psiMethod.getParameterList().getParametersCount() == 0) {
        myBodyContainsForbiddenRefs = true;
      }
    }

    @Override
    public void visitThisExpression(PsiThisExpression expression) {
      if (myBodyContainsForbiddenRefs) {
        return;
      }

      if (expression.getQualifier() == null) {
        myBodyContainsForbiddenRefs = true;
      }
    }

    @Override
    public void visitSuperExpression(PsiSuperExpression expression) {
      if (myBodyContainsForbiddenRefs) {
        return;
      }

      if (expression.getQualifier() == null) {
        myBodyContainsForbiddenRefs = true;
      }
    }

    @Override
    public void visitVariable(PsiVariable variable) {
      if (myBodyContainsForbiddenRefs) {
        return;
      }

      super.visitVariable(variable);
    }

    @Override
    public void visitReferenceExpression(PsiReferenceExpression expression) {
      if (myBodyContainsForbiddenRefs) {
        return;
      }

      super.visitReferenceExpression(expression);
      if (!(expression.getParent() instanceof PsiMethodCallExpression)) {
        final PsiMember member = PsiTreeUtil.getParentOfType(myAnonymClass, PsiMember.class);
        if (member instanceof PsiField || member instanceof PsiClassInitializer) {
          final PsiElement resolved = expression.resolve();
          final PsiClass memberContainingClass = member.getContainingClass();
          if (resolved instanceof PsiField &&
              memberContainingClass != null &&
              PsiTreeUtil.isAncestor(((PsiField) resolved).getContainingClass(), memberContainingClass, false) &&
              expression.getQualifierExpression() == null) {
            final PsiExpression initializer = ((PsiField) resolved).getInitializer();
            if (initializer == null ||
                resolved == member ||
                initializer.getTextOffset() > myAnonymClass.getTextOffset() && ((PsiField) resolved).hasModifierProperty(PsiModifier.STATIC) == member.hasModifierProperty(PsiModifier
                    .STATIC)) {
              myBodyContainsForbiddenRefs = true;
            }
          }
        } else {
          final PsiMethod method = PsiTreeUtil.getParentOfType(myAnonymClass, PsiMethod.class);
          if (method != null && method.isConstructor()) {
            final PsiElement resolved = expression.resolve();
            if (resolved instanceof PsiField &&
                ((PsiField) resolved).hasModifierProperty(PsiModifier.FINAL) &&
                ((PsiField) resolved).getInitializer() == null &&
                ((PsiField) resolved).getContainingClass() == method.getContainingClass()) {
              try {
                final PsiCodeBlock constructorBody = method.getBody();
                if (constructorBody != null) {
                  final ControlFlow flow = HighlightControlFlowUtil.getControlFlowNoConstantEvaluate(constructorBody);
                  final int startOffset = flow.getStartOffset(myAnonymClass);
                  final Collection<PsiVariable> writtenVariables = ControlFlowUtil.getWrittenVariables(flow, 0, startOffset, false);
                  if (!writtenVariables.contains(resolved)) {
                    myBodyContainsForbiddenRefs = true;
                  }
                }
              } catch (AnalysisCanceledException e) {
                myBodyContainsForbiddenRefs = true;
              }
            }
          }
        }
      }
    }

    public boolean hasForbiddenRefs() {
      return myBodyContainsForbiddenRefs;
    }
  }
}
