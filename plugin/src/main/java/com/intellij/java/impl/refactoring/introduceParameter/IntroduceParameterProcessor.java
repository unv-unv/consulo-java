/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: dsl
 * Date: 07.05.2002
 * Time: 11:17:31
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.java.impl.refactoring.introduceParameter;

import com.intellij.java.impl.refactoring.IntroduceParameterRefactoring;
import com.intellij.java.impl.refactoring.changeSignature.ChangeSignatureProcessor;
import com.intellij.java.impl.refactoring.introduceVariable.IntroduceVariableBase;
import com.intellij.java.impl.refactoring.util.ConflictsUtil;
import com.intellij.java.impl.refactoring.util.FieldConflictsResolver;
import com.intellij.java.impl.refactoring.util.RefactoringUtil;
import com.intellij.java.impl.refactoring.util.occurrences.ExpressionOccurrenceManager;
import com.intellij.java.impl.refactoring.util.occurrences.LocalVariableOccurrenceManager;
import com.intellij.java.impl.refactoring.util.occurrences.OccurrenceManager;
import com.intellij.java.impl.refactoring.util.usageInfo.DefaultConstructorImplicitUsageInfo;
import com.intellij.java.impl.refactoring.util.usageInfo.NoConstructorClassUsageInfo;
import com.intellij.java.indexing.search.searches.MethodReferencesSearch;
import com.intellij.java.indexing.search.searches.OverridingMethodsSearch;
import com.intellij.java.language.impl.codeInsight.ChangeContextUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PropertyUtil;
import consulo.language.editor.refactoring.BaseRefactoringProcessor;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.ui.RefactoringUIUtil;
import consulo.language.editor.refactoring.util.CommonRefactoringUtil;
import consulo.language.findUsage.DescriptiveNameUtil;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiManager;
import consulo.language.psi.PsiReference;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.usage.UsageInfo;
import consulo.usage.UsageViewDescriptor;
import consulo.usage.UsageViewUtil;
import consulo.util.collection.MultiMap;
import consulo.util.collection.primitive.ints.IntList;
import consulo.util.lang.Pair;
import consulo.util.lang.ref.Ref;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class IntroduceParameterProcessor extends BaseRefactoringProcessor implements IntroduceParameterData
{
	private static final Logger LOG = Logger.getInstance(IntroduceParameterProcessor.class);

	private final PsiMethod myMethodToReplaceIn;
	private final PsiMethod myMethodToSearchFor;
	private PsiExpression myParameterInitializer;
	private final PsiExpression myExpressionToSearch;
	private final PsiLocalVariable myLocalVariable;
	private final boolean myRemoveLocalVariable;
	private final String myParameterName;
	private final boolean myReplaceAllOccurences;

	private int myReplaceFieldsWithGetters;
	private final boolean myDeclareFinal;
	private final boolean myGenerateDelegate;
	private PsiType myForcedType;
	private final IntList myParametersToRemove;
	private final PsiManager myManager;
	private JavaExpressionWrapper myInitializerWrapper;
	private boolean myHasConflicts;

	/**
	 * if expressionToSearch is null, search for localVariable
	 */
	public IntroduceParameterProcessor(@Nonnull Project project,
									   PsiMethod methodToReplaceIn,
									   @Nonnull PsiMethod methodToSearchFor,
									   PsiExpression parameterInitializer,
									   PsiExpression expressionToSearch,
									   PsiLocalVariable localVariable,
									   boolean removeLocalVariable,
									   String parameterName,
									   boolean replaceAllOccurences,
									   int replaceFieldsWithGetters,
									   boolean declareFinal,
									   boolean generateDelegate,
									   PsiType forcedType,
									   @Nonnull IntList parametersToRemove)
	{
		super(project);

		myMethodToReplaceIn = methodToReplaceIn;
		myMethodToSearchFor = methodToSearchFor;
		myParameterInitializer = parameterInitializer;
		myExpressionToSearch = expressionToSearch;

		myLocalVariable = localVariable;
		myRemoveLocalVariable = removeLocalVariable;
		myParameterName = parameterName;
		myReplaceAllOccurences = replaceAllOccurences;
		myReplaceFieldsWithGetters = replaceFieldsWithGetters;
		myDeclareFinal = declareFinal;
		myGenerateDelegate = generateDelegate;
		myForcedType = forcedType;
		myManager = PsiManager.getInstance(project);

		myParametersToRemove = parametersToRemove;

		myInitializerWrapper = expressionToSearch == null ? null : new JavaExpressionWrapper(expressionToSearch);
	}

	@Nonnull
	protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages)
	{
		return new IntroduceParameterViewDescriptor(myMethodToSearchFor);
	}

	@Nonnull
	public PsiType getForcedType()
	{
		return myForcedType;
	}

	public void setForcedType(PsiType forcedType)
	{
		myForcedType = forcedType;
	}

	public int getReplaceFieldsWithGetters()
	{
		return myReplaceFieldsWithGetters;
	}

	public void setReplaceFieldsWithGetters(int replaceFieldsWithGetters)
	{
		myReplaceFieldsWithGetters = replaceFieldsWithGetters;
	}

	@Nonnull
	protected UsageInfo[] findUsages()
	{
		ArrayList<UsageInfo> result = new ArrayList<UsageInfo>();

		PsiMethod[] overridingMethods =
				OverridingMethodsSearch.search(myMethodToSearchFor, true).toArray(PsiMethod.EMPTY_ARRAY);
		for(PsiMethod overridingMethod : overridingMethods)
		{
			result.add(new UsageInfo(overridingMethod));
		}
		if(!myGenerateDelegate)
		{
			PsiReference[] refs =
					MethodReferencesSearch.search(myMethodToSearchFor, GlobalSearchScope.projectScope(myProject), true).toArray(PsiReference.EMPTY_ARRAY);


			for(PsiReference ref1 : refs)
			{
				PsiElement ref = ref1.getElement();
				if(ref instanceof PsiMethod && ((PsiMethod) ref).isConstructor())
				{
					DefaultConstructorImplicitUsageInfo implicitUsageInfo =
							new DefaultConstructorImplicitUsageInfo((PsiMethod) ref, ((PsiMethod) ref).getContainingClass(), myMethodToSearchFor);
					result.add(implicitUsageInfo);
				}
				else if(ref instanceof PsiClass)
				{
					result.add(new NoConstructorClassUsageInfo((PsiClass) ref));
				}
				else if(!IntroduceParameterUtil.insideMethodToBeReplaced(ref, myMethodToReplaceIn))
				{
					result.add(new ExternalUsageInfo(ref));
				}
				else
				{
					result.add(new ChangedMethodCallInfo(ref));
				}
			}
		}

		if(myReplaceAllOccurences)
		{
			for(PsiElement expr : getOccurrences())
			{
				result.add(new InternalUsageInfo(expr));
			}
		}
		else
		{
			if(myExpressionToSearch != null && myExpressionToSearch.isValid())
			{
				result.add(new InternalUsageInfo(myExpressionToSearch));
			}
		}

		final UsageInfo[] usageInfos = result.toArray(new UsageInfo[result.size()]);
		return UsageViewUtil.removeDuplicatedUsages(usageInfos);
	}

	protected PsiElement[] getOccurrences()
	{
		final OccurrenceManager occurrenceManager;
		if(myLocalVariable == null)
		{
			occurrenceManager = new ExpressionOccurrenceManager(myExpressionToSearch, myMethodToReplaceIn, null);
		}
		else
		{
			occurrenceManager = new LocalVariableOccurrenceManager(myLocalVariable, null);
		}
		return occurrenceManager.getOccurrences();
	}

	public boolean hasConflicts()
	{
		return myHasConflicts;
	}

	private static class ReferencedElementsCollector extends JavaRecursiveElementWalkingVisitor
	{
		private final Set<PsiElement> myResult = new HashSet<PsiElement>();

		@Override
		public void visitReferenceExpression(PsiReferenceExpression expression)
		{
			visitReferenceElement(expression);
		}

		@Override
		public void visitReferenceElement(PsiJavaCodeReferenceElement reference)
		{
			super.visitReferenceElement(reference);
			final PsiElement element = reference.resolve();
			if(element != null)
			{
				myResult.add(element);
			}
		}
	}

	protected boolean preprocessUsages(Ref<UsageInfo[]> refUsages)
	{
		UsageInfo[] usagesIn = refUsages.get();
		MultiMap<PsiElement, String> conflicts = new MultiMap<PsiElement, String>();

		AnySameNameVariables anySameNameVariables = new AnySameNameVariables();
		myMethodToReplaceIn.accept(anySameNameVariables);
		final Pair<PsiElement, String> conflictPair = anySameNameVariables.getConflict();
		if(conflictPair != null)
		{
			conflicts.putValue(conflictPair.first, conflictPair.second);
		}

		if(!myGenerateDelegate)
		{
			detectAccessibilityConflicts(usagesIn, conflicts);
		}

		if(myParameterInitializer != null && !myMethodToReplaceIn.hasModifierProperty(PsiModifier.PRIVATE))
		{
			final AnySupers anySupers = new AnySupers();
			myParameterInitializer.accept(anySupers);
			if(anySupers.isResult())
			{
				for(UsageInfo usageInfo : usagesIn)
				{
					if(!(usageInfo.getElement() instanceof PsiMethod) && !(usageInfo instanceof InternalUsageInfo))
					{
						if(!PsiTreeUtil.isAncestor(myMethodToReplaceIn.getContainingClass(), usageInfo.getElement(), false))
						{
							conflicts.putValue(
								myParameterInitializer,
								RefactoringLocalize.parameterInitializerContains0ButNotAllCallsToMethodAreInItsClass(
									CommonRefactoringUtil.htmlEmphasize(PsiKeyword.SUPER)
								).get()
							);
							break;
						}
					}
				}
			}
		}

		for(IntroduceParameterMethodUsagesProcessor processor : IntroduceParameterMethodUsagesProcessor.EP_NAME.getExtensions())
		{
			processor.findConflicts(this, refUsages.get(), conflicts);
		}

		myHasConflicts = !conflicts.isEmpty();
		return showConflicts(conflicts, usagesIn);
	}

	private void detectAccessibilityConflicts(final UsageInfo[] usageArray, MultiMap<PsiElement, String> conflicts)
	{
		if(myParameterInitializer != null)
		{
			final ReferencedElementsCollector collector = new ReferencedElementsCollector();
			myParameterInitializer.accept(collector);
			final Set<PsiElement> result = collector.myResult;
			if(!result.isEmpty())
			{
				for(final UsageInfo usageInfo : usageArray)
				{
					if(usageInfo instanceof ExternalUsageInfo && IntroduceParameterUtil.isMethodUsage(usageInfo))
					{
						final PsiElement place = usageInfo.getElement();
						for(PsiElement element : result)
						{
							if(element instanceof PsiField && myReplaceFieldsWithGetters != IntroduceParameterRefactoring.REPLACE_FIELDS_WITH_GETTERS_NONE)
							{ //check getter access instead
								final PsiClass psiClass = ((PsiField) element).getContainingClass();
								LOG.assertTrue(psiClass != null);
								final PsiMethod method = psiClass.findMethodBySignature(PropertyUtil.generateGetterPrototype((PsiField) element), true);
								if(method != null)
								{
									element = method;
								}
							}
							if(element instanceof PsiMember &&
									!JavaPsiFacade.getInstance(myProject).getResolveHelper().isAccessible((PsiMember) element, place, null))
							{
								LocalizeValue message = RefactoringLocalize.zeroIsNotAccessibleFrom1ValueForIntroducedParameterInThatMethodCallWillBeIncorrect(
									RefactoringUIUtil.getDescription(element, true),
									RefactoringUIUtil.getDescription(ConflictsUtil.getContainer(place), true)
								);
								conflicts.putValue(element, message.get());
							}
						}
					}
				}
			}
		}
	}

	public static class AnySupers extends JavaRecursiveElementWalkingVisitor
	{
		private boolean myResult = false;

		@Override
		public void visitSuperExpression(PsiSuperExpression expression)
		{
			myResult = true;
		}

		public boolean isResult()
		{
			return myResult;
		}

		@Override
		public void visitReferenceExpression(PsiReferenceExpression expression)
		{
			visitElement(expression);
		}
	}

	public class AnySameNameVariables extends JavaRecursiveElementWalkingVisitor
	{
		private Pair<PsiElement, String> conflict = null;

		public Pair<PsiElement, String> getConflict()
		{
			return conflict;
		}

		@Override
		public void visitVariable(PsiVariable variable)
		{
			if(variable == myLocalVariable)
			{
				return;
			}
			if(myParameterName.equals(variable.getName()))
			{
				LocalizeValue descr = RefactoringLocalize.thereIsAlreadyA0ItWillConflictWithAnIntroducedParameter(
					RefactoringUIUtil.getDescription(variable, true)
				);

				conflict = Pair.<PsiElement, String>create(variable, CommonRefactoringUtil.capitalize(descr.get()));
			}
		}

		@Override
		public void visitReferenceExpression(PsiReferenceExpression expression)
		{
		}

		@Override
		public void visitElement(PsiElement element)
		{
			if(conflict != null)
			{
				return;
			}
			super.visitElement(element);
		}
	}

	protected void performRefactoring(UsageInfo[] usages)
	{
		try
		{
			PsiElementFactory factory = JavaPsiFacade.getInstance(myManager.getProject()).getElementFactory();
			PsiType initializerType = getInitializerType(myForcedType, myParameterInitializer, myLocalVariable);
			setForcedType(initializerType);

			// Converting myParameterInitializer
			if(myParameterInitializer == null)
			{
				LOG.assertTrue(myLocalVariable != null);
				myParameterInitializer = factory.createExpressionFromText(myLocalVariable.getName(), myLocalVariable);
			}
			else if(myParameterInitializer instanceof PsiArrayInitializerExpression)
			{
				final PsiExpression newExprArrayInitializer =
						RefactoringUtil.createNewExpressionFromArrayInitializer((PsiArrayInitializerExpression) myParameterInitializer, initializerType);
				myParameterInitializer = (PsiExpression) myParameterInitializer.replace(newExprArrayInitializer);
			}

			myInitializerWrapper = new JavaExpressionWrapper(myParameterInitializer);

			// Changing external occurences (the tricky part)

			IntroduceParameterUtil.processUsages(usages, this);

			if(myGenerateDelegate)
			{
				generateDelegate(myMethodToReplaceIn);
				if(myMethodToReplaceIn != myMethodToSearchFor)
				{
					final PsiMethod method = generateDelegate(myMethodToSearchFor);
					if(method.getContainingClass().isInterface())
					{
						final PsiCodeBlock block = method.getBody();
						if(block != null)
						{
							block.delete();
						}
					}
				}
			}

			// Changing signature of initial method
			// (signature of myMethodToReplaceIn will be either changed now or have already been changed)
			LOG.assertTrue(initializerType.isValid());
			final FieldConflictsResolver fieldConflictsResolver = new FieldConflictsResolver(myParameterName, myMethodToReplaceIn.getBody());
			IntroduceParameterUtil.changeMethodSignatureAndResolveFieldConflicts(new UsageInfo(myMethodToReplaceIn), usages, this);
			if(myMethodToSearchFor != myMethodToReplaceIn)
			{
				IntroduceParameterUtil.changeMethodSignatureAndResolveFieldConflicts(new UsageInfo(myMethodToSearchFor), usages, this);
			}
			ChangeContextUtil.clearContextInfo(myParameterInitializer);

			// Replacing expression occurrences
			for(UsageInfo usage : usages)
			{
				if(usage instanceof ChangedMethodCallInfo)
				{
					PsiElement element = usage.getElement();

					processChangedMethodCall(element);
				}
				else if(usage instanceof InternalUsageInfo)
				{
					PsiElement element = usage.getElement();
					if(element instanceof PsiExpression)
					{
						element = RefactoringUtil.outermostParenthesizedExpression((PsiExpression) element);
					}
					if(element != null)
					{
						if(element.getParent() instanceof PsiExpressionStatement)
						{
							element.getParent().delete();
						}
						else
						{
							PsiExpression newExpr = factory.createExpressionFromText(myParameterName, element);
							IntroduceVariableBase.replace((PsiExpression) element, newExpr, myProject);
						}
					}
				}
			}

			if(myLocalVariable != null && myRemoveLocalVariable)
			{
				myLocalVariable.normalizeDeclaration();
				myLocalVariable.getParent().delete();
			}
			fieldConflictsResolver.fix();
		}
		catch(IncorrectOperationException ex)
		{
			LOG.error(ex);
		}
	}

	private PsiMethod generateDelegate(final PsiMethod methodToReplaceIn) throws IncorrectOperationException
	{
		final PsiMethod delegate = (PsiMethod) methodToReplaceIn.copy();
		final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(myManager.getProject()).getElementFactory();
		ChangeSignatureProcessor.makeEmptyBody(elementFactory, delegate);
		final PsiCallExpression callExpression = ChangeSignatureProcessor.addDelegatingCallTemplate(delegate, delegate.getName());
		final PsiExpressionList argumentList = callExpression.getArgumentList();
		assert argumentList != null;
		final PsiParameter[] psiParameters = methodToReplaceIn.getParameterList().getParameters();

		final PsiParameter anchorParameter = getAnchorParameter(methodToReplaceIn);
		if(psiParameters.length == 0)
		{
			argumentList.add(myParameterInitializer);
		}
		else
		{
			if(anchorParameter == null)
			{
				argumentList.add(myParameterInitializer);
			}
			for(int i = 0; i < psiParameters.length; i++)
			{
				PsiParameter psiParameter = psiParameters[i];
				if(!myParametersToRemove.contains(i))
				{
					final PsiExpression expression = elementFactory.createExpressionFromText(psiParameter.getName(), delegate);
					argumentList.add(expression);
				}
				if(psiParameter == anchorParameter)
				{
					argumentList.add(myParameterInitializer);
				}
			}
		}

		return (PsiMethod) methodToReplaceIn.getContainingClass().addBefore(delegate, methodToReplaceIn);
	}

	static PsiType getInitializerType(PsiType forcedType, PsiExpression parameterInitializer, PsiLocalVariable localVariable)
	{
		final PsiType initializerType;
		if(forcedType == null)
		{
			if(parameterInitializer == null)
			{
				if(localVariable != null)
				{
					initializerType = localVariable.getType();
				}
				else
				{
					LOG.assertTrue(false);
					initializerType = null;
				}
			}
			else
			{
				if(localVariable == null)
				{
					initializerType = RefactoringUtil.getTypeByExpressionWithExpectedType(parameterInitializer);
				}
				else
				{
					initializerType = localVariable.getType();
				}
			}
		}
		else
		{
			initializerType = forcedType;
		}
		return initializerType;
	}

	private void processChangedMethodCall(PsiElement element) throws IncorrectOperationException
	{
		if(element.getParent() instanceof PsiMethodCallExpression)
		{
			PsiMethodCallExpression methodCall = (PsiMethodCallExpression) element.getParent();

			if(myMethodToReplaceIn == myMethodToSearchFor && PsiTreeUtil.isAncestor(methodCall, myParameterInitializer, false))
			{
				return;
			}

			PsiElementFactory factory = JavaPsiFacade.getInstance(methodCall.getProject()).getElementFactory();
			PsiExpression expression = factory.createExpressionFromText(myParameterName, null);
			final PsiExpressionList argList = methodCall.getArgumentList();
			final PsiExpression[] exprs = argList.getExpressions();

			boolean first = false;
			PsiElement anchor = null;
			if(myMethodToSearchFor.isVarArgs())
			{
				final int oldParamCount = myMethodToSearchFor.getParameterList().getParametersCount() - 1;
				if(exprs.length >= oldParamCount)
				{
					if(oldParamCount > 1)
					{
						anchor = exprs[oldParamCount - 2];
					}
					else
					{
						first = true;
						anchor = null;
					}
				}
				else
				{
					anchor = exprs[exprs.length - 1];
				}
			}
			else if(exprs.length > 0)
			{
				anchor = exprs[exprs.length - 1];
			}

			if(anchor != null)
			{
				argList.addAfter(expression, anchor);
			}
			else
			{
				if(first && exprs.length > 0)
				{
					argList.addBefore(expression, exprs[0]);
				}
				else
				{
					argList.add(expression);
				}
			}

			removeParametersFromCall(argList);
		}
		else
		{
			LOG.error(element.getParent());
		}
	}

	private void removeParametersFromCall(final PsiExpressionList argList)
	{
		final PsiExpression[] exprs = argList.getExpressions();
		for(int i = myParametersToRemove.size() - 1; i >= 0; i--)
		{
			int paramNum = myParametersToRemove.get(i);
			if(paramNum < exprs.length)
			{
				try
				{
					exprs[paramNum].delete();
				}
				catch(IncorrectOperationException e)
				{
					LOG.error(e);
				}
			}
		}
	}

	protected String getCommandName()
	{
		return RefactoringLocalize.introduceParameterCommand(DescriptiveNameUtil.getDescriptiveName(myMethodToReplaceIn)).get();
	}

	@Nullable
	private static PsiParameter getAnchorParameter(PsiMethod methodToReplaceIn)
	{
		PsiParameterList parameterList = methodToReplaceIn.getParameterList();
		final PsiParameter anchorParameter;
		final PsiParameter[] parameters = parameterList.getParameters();
		final int length = parameters.length;
		if(!methodToReplaceIn.isVarArgs())
		{
			anchorParameter = length > 0 ? parameters[length - 1] : null;
		}
		else
		{
			LOG.assertTrue(length > 0);
			LOG.assertTrue(parameters[length - 1].isVarArgs());
			anchorParameter = length > 1 ? parameters[length - 2] : null;
		}
		return anchorParameter;
	}

	public PsiMethod getMethodToReplaceIn()
	{
		return myMethodToReplaceIn;
	}

	@Nonnull
	public PsiMethod getMethodToSearchFor()
	{
		return myMethodToSearchFor;
	}

	public JavaExpressionWrapper getParameterInitializer()
	{
		return myInitializerWrapper;
	}

	@Nonnull
	public String getParameterName()
	{
		return myParameterName;
	}

	public boolean isDeclareFinal()
	{
		return myDeclareFinal;
	}

	public boolean isGenerateDelegate()
	{
		return myGenerateDelegate;
	}

	@Nonnull
	public IntList getParametersToRemove()
	{
		return myParametersToRemove;
	}

	@Nonnull
	public Project getProject()
	{
		return myProject;
	}

}
