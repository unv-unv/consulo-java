/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.convertToInstanceMethod;

import com.intellij.java.impl.refactoring.move.moveInstanceMethod.MoveInstanceMethodViewDescriptor;
import com.intellij.java.impl.refactoring.util.ConflictsUtil;
import com.intellij.java.impl.refactoring.util.RefactoringConflictsUtil;
import com.intellij.java.impl.refactoring.util.RefactoringHierarchyUtil;
import com.intellij.java.impl.refactoring.util.RefactoringUtil;
import com.intellij.java.language.impl.codeInsight.ChangeContextUtil;
import com.intellij.java.language.impl.psi.impl.source.javadoc.PsiDocParamRef;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.javadoc.PsiDocTagValue;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import com.intellij.java.language.util.VisibilityUtil;
import consulo.language.editor.refactoring.BaseRefactoringProcessor;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.ui.RefactoringUIUtil;
import consulo.language.editor.refactoring.util.CommonRefactoringUtil;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiReference;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.scope.LocalSearchScope;
import consulo.language.psi.search.ReferencesSearch;
import consulo.language.util.IncorrectOperationException;
import consulo.localHistory.LocalHistory;
import consulo.localHistory.LocalHistoryAction;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.usage.UsageInfo;
import consulo.usage.UsageViewDescriptor;
import consulo.util.collection.MultiMap;
import consulo.util.dataholder.Key;
import consulo.util.lang.ref.SimpleReference;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

/**
 * @author dsl
 */
public class ConvertToInstanceMethodProcessor extends BaseRefactoringProcessor {
    private static final Logger LOG = Logger.getInstance(ConvertToInstanceMethodProcessor.class);
    private PsiMethod myMethod;
    private PsiParameter myTargetParameter;
    private PsiClass myTargetClass;
    private Map<PsiTypeParameter, PsiTypeParameter> myTypeParameterReplacements;
    private static final Key<PsiTypeParameter> BIND_TO_TYPE_PARAMETER = Key.create("REPLACEMENT");
    private final String myOldVisibility;
    private final String myNewVisibility;

    public ConvertToInstanceMethodProcessor(
        final Project project,
        final PsiMethod method,
        final PsiParameter targetParameter,
        final String newVisibility
    ) {
        super(project);
        myMethod = method;
        myTargetParameter = targetParameter;
        LOG.assertTrue(method.hasModifierProperty(PsiModifier.STATIC));
        LOG.assertTrue(myTargetParameter.getDeclarationScope() == myMethod);
        LOG.assertTrue(myTargetParameter.getType() instanceof PsiClassType);
        final PsiType type = myTargetParameter.getType();
        LOG.assertTrue(type instanceof PsiClassType);
        myTargetClass = ((PsiClassType)type).resolve();
        myOldVisibility = VisibilityUtil.getVisibilityModifier(method.getModifierList());
        myNewVisibility = newVisibility;
    }

    public PsiClass getTargetClass() {
        return myTargetClass;
    }

    @Nonnull
    protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
        return new MoveInstanceMethodViewDescriptor(myMethod, myTargetParameter, myTargetClass);
    }

    protected void refreshElements(PsiElement[] elements) {
        LOG.assertTrue(elements.length == 3);
        myMethod = (PsiMethod)elements[0];
        myTargetParameter = (PsiParameter)elements[1];
        myTargetClass = (PsiClass)elements[2];
    }

    @Nonnull
    protected UsageInfo[] findUsages() {
        LOG.assertTrue(myTargetParameter.getDeclarationScope() == myMethod);
        final Project project = myMethod.getProject();

        final PsiReference[] methodReferences =
            ReferencesSearch.search(myMethod, GlobalSearchScope.projectScope(project), false).toArray(new PsiReference[0]);
        List<UsageInfo> result = new ArrayList<UsageInfo>();
        for (final PsiReference ref : methodReferences) {
            final PsiElement element = ref.getElement();
            if (element instanceof PsiReferenceExpression) {
                if (element.getParent() instanceof PsiMethodCallExpression) {
                    result.add(new MethodCallUsageInfo((PsiMethodCallExpression)element.getParent()));
                }
            }
            else if (element instanceof PsiDocTagValue) {
                result.add(new JavaDocUsageInfo(ref)); //TODO:!!!
            }
        }

        for (final PsiReference ref : ReferencesSearch.search(myTargetParameter, new LocalSearchScope(myMethod), false)) {
            final PsiElement element = ref.getElement();
            if (element instanceof PsiReferenceExpression || element instanceof PsiDocParamRef) {
                result.add(new ParameterUsageInfo(ref));
            }
        }

        if (myTargetClass.isInterface()) {
            PsiClass[] implementingClasses = RefactoringHierarchyUtil.findImplementingClasses(myTargetClass);
            for (final PsiClass implementingClass : implementingClasses) {
                result.add(new ImplementingClassUsageInfo(implementingClass));
            }
        }

        return result.toArray(new UsageInfo[result.size()]);
    }

    @Override
    @RequiredUIAccess
    protected boolean preprocessUsages(@Nonnull SimpleReference<UsageInfo[]> refUsages) {
        UsageInfo[] usagesIn = refUsages.get();
        MultiMap<PsiElement, String> conflicts = new MultiMap<>();
        Set<PsiMember> methods = Collections.singleton((PsiMember)myMethod);
        if (!myTargetClass.isInterface()) {
            RefactoringConflictsUtil.analyzeAccessibilityConflicts(methods, myTargetClass, conflicts, myNewVisibility);
        }
        else {
            for (UsageInfo usage : usagesIn) {
                if (usage instanceof ImplementingClassUsageInfo implementingClassUsageInfo) {
                    RefactoringConflictsUtil.analyzeAccessibilityConflicts(
                        methods,
                        implementingClassUsageInfo.getPsiClass(),
                        conflicts,
                        PsiModifier.PUBLIC
                    );
                }
            }
        }

        for (UsageInfo usageInfo : usagesIn) {
            if (usageInfo instanceof MethodCallUsageInfo methodCallUsageInfo) {
                PsiMethodCallExpression methodCall = methodCallUsageInfo.getMethodCall();
                PsiExpression[] expressions = methodCall.getArgumentList().getExpressions();
                int index = myMethod.getParameterList().getParameterIndex(myTargetParameter);
                if (index < expressions.length) {
                    PsiExpression instanceValue = expressions[index];
                    instanceValue = RefactoringUtil.unparenthesizeExpression(instanceValue);
                    if (instanceValue instanceof PsiLiteralExpression && ((PsiLiteralExpression)instanceValue).getValue() == null) {
                        LocalizeValue message = RefactoringLocalize.zeroContainsCallWithNullArgumentForParameter1(
                            RefactoringUIUtil.getDescription(ConflictsUtil.getContainer(methodCall), true),
                            CommonRefactoringUtil.htmlEmphasize(myTargetParameter.getName())
                        );
                        conflicts.putValue(methodCall, message.get());
                    }
                }
            }
        }

        return showConflicts(conflicts, usagesIn);
    }

    @Override
    @RequiredUIAccess
    protected void performRefactoring(@Nonnull UsageInfo[] usages) {
        if (!CommonRefactoringUtil.checkReadOnlyStatus(myProject, myTargetClass)) {
            return;
        }
        LocalHistoryAction a = LocalHistory.getInstance().startAction(getCommandName());
        try {
            doRefactoring(usages);
        }
        catch (IncorrectOperationException e) {
            LOG.error(e);
        }
        finally {
            a.finish();
        }
    }

    private void doRefactoring(UsageInfo[] usages) throws IncorrectOperationException {
        myTypeParameterReplacements = buildTypeParameterReplacements();
        List<PsiClass> inheritors = new ArrayList<>();

        CommonRefactoringUtil.sortDepthFirstRightLeftOrder(usages);

        // Process usages
        for (UsageInfo usage : usages) {
            if (usage instanceof MethodCallUsageInfo) {
                processMethodCall((MethodCallUsageInfo)usage);
            }
            else if (usage instanceof ParameterUsageInfo) {
                processParameterUsage((ParameterUsageInfo)usage);
            }
            else if (usage instanceof ImplementingClassUsageInfo) {
                inheritors.add(((ImplementingClassUsageInfo)usage).getPsiClass());
            }
        }

        prepareTypeParameterReplacement();
        myTargetParameter.delete();
        ChangeContextUtil.encodeContextInfo(myMethod, true);
        if (!myTargetClass.isInterface()) {
            PsiMethod method = addMethodToClass(myTargetClass);
            fixVisibility(method, usages);
        }
        else {
            PsiMethod interfaceMethod = addMethodToClass(myTargetClass);
            PsiModifierList modifierList = interfaceMethod.getModifierList();
            modifierList.setModifierProperty(PsiModifier.PRIVATE, false);
            modifierList.setModifierProperty(PsiModifier.PUBLIC, false);
            modifierList.setModifierProperty(PsiModifier.PROTECTED, false);
            RefactoringUtil.makeMethodAbstract(myTargetClass, interfaceMethod);

            for (PsiClass psiClass : inheritors) {
                PsiMethod newMethod = addMethodToClass(psiClass);
                PsiUtil.setModifierProperty(
                    newMethod,
                    myNewVisibility != null && !myNewVisibility.equals(VisibilityUtil.ESCALATE_VISIBILITY) ? myNewVisibility
                        : PsiModifier.PUBLIC,
                    true
                );
            }
        }
        myMethod.delete();
    }

    private void fixVisibility(PsiMethod method, UsageInfo[] usages) throws IncorrectOperationException {
        PsiModifierList modifierList = method.getModifierList();
        if (VisibilityUtil.ESCALATE_VISIBILITY.equals(myNewVisibility)) {
            for (UsageInfo usage : usages) {
                if (usage instanceof MethodCallUsageInfo) {
                    PsiElement place = usage.getElement();
                    if (place != null) {
                        VisibilityUtil.escalateVisibility(method, place);
                    }
                }
            }
        }
        else if (myNewVisibility != null && !myNewVisibility.equals(myOldVisibility)) {
            modifierList.setModifierProperty(myNewVisibility, true);
        }
    }

    private void prepareTypeParameterReplacement() throws IncorrectOperationException {
        if (myTypeParameterReplacements == null) {
            return;
        }
        final Collection<PsiTypeParameter> typeParameters = myTypeParameterReplacements.keySet();
        for (final PsiTypeParameter parameter : typeParameters) {
            for (final PsiReference reference : ReferencesSearch.search(parameter, new LocalSearchScope(myMethod), false)) {
                if (reference.getElement() instanceof PsiJavaCodeReferenceElement) {
                    reference.getElement().putCopyableUserData(BIND_TO_TYPE_PARAMETER, myTypeParameterReplacements.get(parameter));
                }
            }
        }
        final Set<PsiTypeParameter> methodTypeParameters = myTypeParameterReplacements.keySet();
        for (final PsiTypeParameter methodTypeParameter : methodTypeParameters) {
            methodTypeParameter.delete();
        }
    }

    private PsiMethod addMethodToClass(final PsiClass targetClass) throws IncorrectOperationException {
        final PsiMethod newMethod = (PsiMethod)targetClass.add(myMethod);
        final PsiModifierList modifierList = newMethod.getModifierList();
        modifierList.setModifierProperty(PsiModifier.STATIC, false);
        ChangeContextUtil.decodeContextInfo(newMethod, null, null);
        if (myTypeParameterReplacements == null) {
            return newMethod;
        }
        final Map<PsiTypeParameter, PsiTypeParameter> additionalReplacements;
        if (targetClass != myTargetClass) {
            final PsiSubstitutor superClassSubstitutor =
                TypeConversionUtil.getSuperClassSubstitutor(myTargetClass, targetClass, PsiSubstitutor.EMPTY);
            final Map<PsiTypeParameter, PsiTypeParameter> map = calculateReplacementMap(superClassSubstitutor, myTargetClass, targetClass);
            if (map == null) {
                return newMethod;
            }
            additionalReplacements = new HashMap<PsiTypeParameter, PsiTypeParameter>();
            for (final Map.Entry<PsiTypeParameter, PsiTypeParameter> entry : map.entrySet()) {
                additionalReplacements.put(entry.getValue(), entry.getKey());
            }
        }
        else {
            additionalReplacements = null;
        }
        newMethod.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
                PsiTypeParameter typeParameterToBind = reference.getCopyableUserData(BIND_TO_TYPE_PARAMETER);
                if (typeParameterToBind != null) {
                    reference.putCopyableUserData(BIND_TO_TYPE_PARAMETER, null);
                    try {
                        if (additionalReplacements != null) {
                            typeParameterToBind = additionalReplacements.get(typeParameterToBind);
                        }
                        reference.bindToElement(typeParameterToBind);
                    }
                    catch (IncorrectOperationException e) {
                        LOG.error(e);
                    }
                }
                else {
                    visitElement(reference);
                }
            }
        });
        return newMethod;
    }

    private void processParameterUsage(ParameterUsageInfo usage) throws IncorrectOperationException {
        final PsiReference reference = usage.getReferenceExpression();
        if (reference instanceof PsiReferenceExpression) {
            final PsiReferenceExpression referenceExpression = (PsiReferenceExpression)reference;
            if (referenceExpression.getParent() instanceof PsiReferenceExpression) {
                // todo: check for correctness
                referenceExpression.delete();
            }
            else {
                final PsiExpression expression =
                    JavaPsiFacade.getInstance(myMethod.getProject()).getElementFactory().createExpressionFromText("this", null);
                referenceExpression.replace(expression);
            }
        }
        else {
            final PsiElement element = reference.getElement();
            if (element instanceof PsiDocParamRef) {
                element.getParent().delete();
            }
        }
    }

    private void processMethodCall(MethodCallUsageInfo usageInfo) throws IncorrectOperationException {
        PsiMethodCallExpression methodCall = usageInfo.getMethodCall();
        PsiParameterList parameterList = myMethod.getParameterList();
        PsiElementFactory factory = JavaPsiFacade.getInstance(myMethod.getProject()).getElementFactory();
        int parameterIndex = parameterList.getParameterIndex(myTargetParameter);
        PsiExpression[] arguments = methodCall.getArgumentList().getExpressions();
        if (arguments.length <= parameterIndex) {
            return;
        }
        final PsiReferenceExpression methodExpression = methodCall.getMethodExpression();
        final PsiExpression qualifier;
        if (methodExpression.getQualifierExpression() != null) {
            qualifier = methodExpression.getQualifierExpression();
        }
        else {
            final PsiReferenceExpression newRefExpr =
                (PsiReferenceExpression)factory.createExpressionFromText("x." + myMethod.getName(), null);
            qualifier = ((PsiReferenceExpression)methodExpression.replace(newRefExpr)).getQualifierExpression();
        }
        qualifier.replace(arguments[parameterIndex]);
        arguments[parameterIndex].delete();
    }

    protected String getCommandName() {
        return ConvertToInstanceMethodHandler.REFACTORING_NAME;
    }

    @Nullable
    public Map<PsiTypeParameter, PsiTypeParameter> buildTypeParameterReplacements() {
        final PsiClassType type = (PsiClassType)myTargetParameter.getType();
        final PsiSubstitutor substitutor = type.resolveGenerics().getSubstitutor();
        return calculateReplacementMap(substitutor, myTargetClass, myMethod);
    }

    @Nullable
    private static Map<PsiTypeParameter, PsiTypeParameter> calculateReplacementMap(
        final PsiSubstitutor substitutor,
        final PsiClass targetClass,
        final PsiElement containingElement
    ) {
        final HashMap<PsiTypeParameter, PsiTypeParameter> result = new HashMap<PsiTypeParameter, PsiTypeParameter>();
        for (PsiTypeParameter classTypeParameter : PsiUtil.typeParametersIterable(targetClass)) {
            final PsiType substitution = substitutor.substitute(classTypeParameter);
            if (!(substitution instanceof PsiClassType)) {
                return null;
            }
            final PsiClass aClass = ((PsiClassType)substitution).resolve();
            if (!(aClass instanceof PsiTypeParameter)) {
                return null;
            }
            final PsiTypeParameter methodTypeParameter = (PsiTypeParameter)aClass;
            if (methodTypeParameter.getOwner() != containingElement) {
                return null;
            }
            if (result.keySet().contains(methodTypeParameter)) {
                return null;
            }
            result.put(methodTypeParameter, classTypeParameter);
        }
        return result;
    }

    public PsiMethod getMethod() {
        return myMethod;
    }

    public PsiParameter getTargetParameter() {
        return myTargetParameter;
    }
}
