/*
 * Copyright 2011 Bas Leijdekkers
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
package com.intellij.java.impl.ig.redundancy;

import com.intellij.java.analysis.codeInspection.reference.*;
import com.intellij.java.impl.ig.BaseGlobalInspection;
import com.intellij.java.language.codeInsight.TestFrameworks;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiField;
import com.intellij.java.language.psi.PsiIdentifier;
import com.intellij.java.language.psi.PsiMethod;
import com.siyeh.InspectionGadgetsBundle;
import consulo.language.editor.inspection.CommonProblemDescriptor;
import consulo.language.editor.inspection.GlobalInspectionContext;
import consulo.language.editor.inspection.ProblemDescriptionsProcessor;
import consulo.language.editor.inspection.ProblemHighlightType;
import consulo.language.editor.inspection.reference.RefElement;
import consulo.language.editor.inspection.reference.RefEntity;
import consulo.language.editor.inspection.reference.RefGraphAnnotator;
import consulo.language.editor.inspection.reference.RefManager;
import consulo.language.editor.inspection.scheme.InspectionManager;
import consulo.language.editor.scope.AnalysisScope;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.module.content.ProjectRootManager;
import consulo.util.dataholder.Key;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;

import jakarta.annotation.Nullable;

public abstract class ElementOnlyUsedFromTestCodeInspection extends BaseGlobalInspection
{

	private static final Key<Boolean> ONLY_USED_FROM_TEST_CODE =
			Key.create("ONLY_USED_FROM_TEST_CODE");

	@Nonnull
	@Override
	public String getDisplayName()
	{
		return InspectionGadgetsBundle.message(
				"element.only.used.from.test.code.display.name");
	}

	@Override
	@Nullable
	public RefGraphAnnotator getAnnotator(RefManager refManager)
	{
		return new ElementOnlyUsedFromTestCodeAnnotator();
	}

	@Nullable
	@Override
	public CommonProblemDescriptor[] checkElement(
			RefEntity refEntity, AnalysisScope scope, InspectionManager manager,
			GlobalInspectionContext globalContext,
			ProblemDescriptionsProcessor processor,
			Object state)
	{
		if(!isOnlyUsedFromTestCode(refEntity))
		{
			return null;
		}
		if(!(refEntity instanceof RefJavaElement))
		{
			return null;
		}
		final RefJavaElement javaElement = (RefJavaElement) refEntity;
		if(!javaElement.isReferenced())
		{
			return null;
		}
		final PsiElement element = javaElement.getElement();
		if(element instanceof PsiClass)
		{
			final PsiClass aClass = (PsiClass) element;
			final PsiIdentifier identifier = aClass.getNameIdentifier();
			if(identifier == null)
			{
				return null;
			}
			return new CommonProblemDescriptor[]{
					manager.createProblemDescriptor(identifier,
							InspectionGadgetsBundle.message(
									"class.only.used.from.test.code.problem.descriptor"),
							true, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)
			};
		}
		else if(element instanceof PsiMethod)
		{
			final PsiMethod method = (PsiMethod) element;
			final PsiIdentifier identifier = method.getNameIdentifier();
			if(identifier == null)
			{
				return null;
			}
			return new CommonProblemDescriptor[]{
					manager.createProblemDescriptor(identifier,
							InspectionGadgetsBundle.message(
									"method.only.used.from.test.code.problem.descriptor"),
							true, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)
			};
		}
		else if(element instanceof PsiField)
		{
			final PsiField field = (PsiField) element;
			final PsiIdentifier identifier = field.getNameIdentifier();
			return new CommonProblemDescriptor[]{
					manager.createProblemDescriptor(identifier,
							InspectionGadgetsBundle.message(
									"field.only.used.from.test.code.problem.descriptor"),
							true, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)
			};
		}
		return null;
	}

	private static boolean isInsideTestClass(@Nonnull PsiElement e)
	{
		final PsiClass aClass = getTopLevelParentClass(e);
		return aClass != null && TestFrameworks.getInstance().isTestClass(aClass);
	}

	private static boolean isUnderTestSources(PsiElement e)
	{
		final ProjectRootManager rootManager =
				ProjectRootManager.getInstance(e.getProject());
		final VirtualFile file = e.getContainingFile().getVirtualFile();
		return file != null &&
				rootManager.getFileIndex().isInTestSourceContent(file);
	}

	@Nullable
	public static PsiClass getTopLevelParentClass(PsiElement e)
	{
		PsiClass result = null;
		PsiElement parent = e.getParent();
		while(parent != null && !(parent instanceof PsiFile))
		{
			if(parent instanceof PsiClass)
			{
				result = (PsiClass) parent;
			}
			parent = parent.getParent();
		}
		return result;
	}

	private static boolean isOnlyUsedFromTestCode(RefEntity refElement)
	{
		final Boolean usedFromTestCode =
				refElement.getUserData(ONLY_USED_FROM_TEST_CODE);
		return usedFromTestCode != null && usedFromTestCode.booleanValue();
	}

	private static class ElementOnlyUsedFromTestCodeAnnotator
			extends RefGraphAnnotator
	{

		@Override
		public void onMarkReferenced(RefElement refWhat, RefElement refFrom,
									 boolean referencedFromClassInitializer)
		{
			if(!(refWhat instanceof RefMethod) &&
					!(refWhat instanceof RefField) &&
					!(refWhat instanceof RefClass))
			{
				return;
			}
			if(referencedFromClassInitializer ||
					refFrom instanceof RefImplicitConstructor)
			{
				return;
			}
			final PsiElement whatElement = refWhat.getElement();
			if(isInsideTestClass(whatElement) ||
					isUnderTestSources(whatElement))
			{
				// test code itself is allowed to only be used from test code
				return;
			}
			if(refFrom instanceof RefMethod && refWhat instanceof RefClass)
			{
				final RefMethod method = (RefMethod) refFrom;
				if(method.isConstructor() &&
						method.getOwnerClass() == refWhat)
				{
					// don't count references to class from its own constructor
					return;
				}
			}
			final Boolean onlyUsedFromTestCode =
					refWhat.getUserData(ONLY_USED_FROM_TEST_CODE);
			if(onlyUsedFromTestCode == null)
			{
				refWhat.putUserData(ONLY_USED_FROM_TEST_CODE, Boolean.TRUE);
			}
			else if(!onlyUsedFromTestCode.booleanValue())
			{
				return;
			}
			final PsiElement fromElement = refFrom.getElement();
			if(isInsideTestClass(fromElement) ||
					isUnderTestSources(fromElement))
			{
				return;
			}

			if(refWhat instanceof RefMethod)
			{
				final RefMethod what = (RefMethod) refWhat;
				if(what.isConstructor())
				{
					final RefClass ownerClass = what.getOwnerClass();
					ownerClass.putUserData(ONLY_USED_FROM_TEST_CODE,
							Boolean.FALSE);
					// do count references to class from its own constructor
					// when that constructor is used outside of test code.
				}
			}
			refWhat.putUserData(ONLY_USED_FROM_TEST_CODE, Boolean.FALSE);
		}
	}
}
