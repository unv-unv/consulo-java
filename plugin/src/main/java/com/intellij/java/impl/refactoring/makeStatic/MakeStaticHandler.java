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
* Date: Apr 15, 2002
* Time: 1:25:37 PM
* To change template for new class use
* Code Style | Class Templates options (Tools | IDE Options).
*/
package com.intellij.java.impl.refactoring.makeStatic;

import com.intellij.java.impl.refactoring.HelpID;
import com.intellij.java.indexing.search.searches.MethodReferencesSearch;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.codeStyle.VariableKind;
import consulo.application.progress.ProgressManager;
import consulo.codeEditor.Editor;
import consulo.codeEditor.ScrollType;
import consulo.dataContext.DataContext;
import consulo.language.editor.refactoring.RefactoringBundle;
import consulo.language.editor.refactoring.action.RefactoringActionHandler;
import consulo.language.editor.refactoring.localize.RefactoringLocalize;
import consulo.language.editor.refactoring.util.CommonRefactoringUtil;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class MakeStaticHandler implements RefactoringActionHandler
{
	public static final String REFACTORING_NAME = RefactoringBundle.message("make.method.static.title");
	private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.makeMethodStatic.MakeMethodStaticHandler");

	@Override
	@RequiredUIAccess
	public void invoke(@Nonnull Project project, Editor editor, PsiFile file, DataContext dataContext)
	{
		PsiElement element = dataContext.getData(PsiElement.KEY);
		editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
		if (element == null)
		{
			element = file.findElementAt(editor.getCaretModel().getOffset());
		}

		if (element == null)
		{
			return;
		}
		if (element instanceof PsiIdentifier)
		{
			element = element.getParent();
		}

		if (!(element instanceof PsiTypeParameterListOwner))
		{
			LocalizeValue message =
				RefactoringLocalize.cannotPerformRefactoringWithReason(RefactoringLocalize.errorWrongCaretPositionMethodOrClassName());
			CommonRefactoringUtil.showErrorHint(project, editor, message.get(), REFACTORING_NAME, HelpID.MAKE_METHOD_STATIC);
			return;
		}
		if (LOG.isDebugEnabled())
		{
			LOG.debug("MakeStaticHandler invoked");
		}
		invoke(project, new PsiElement[]{element}, dataContext);
	}

	@Override
	@RequiredUIAccess
	public void invoke(@Nonnull final Project project, @Nonnull PsiElement[] elements, DataContext dataContext)
	{
		if (elements.length != 1 || !(elements[0] instanceof PsiTypeParameterListOwner))
		{
			return;
		}

		final PsiTypeParameterListOwner member = (PsiTypeParameterListOwner) elements[0];
		if (!CommonRefactoringUtil.checkReadOnlyStatus(project, member))
		{
			return;
		}

		String error = validateTarget(member);
		if (error != null)
		{
			Editor editor = dataContext.getData(Editor.KEY);
			CommonRefactoringUtil.showErrorHint(project, editor, error, REFACTORING_NAME, HelpID.MAKE_METHOD_STATIC);
			return;
		}

		invoke(member);
	}

	@RequiredUIAccess
	public static void invoke(final PsiTypeParameterListOwner member)
	{
		final Project project = member.getProject();
		final InternalUsageInfo[] classRefsInMember = MakeStaticUtil.findClassRefsInMember(member, false);

    /*
	String classParameterName = "anObject";
    ParameterTablePanel.VariableData[] fieldParameterData = null;

    */
		AbstractMakeStaticDialog dialog;
		if (project.getApplication().isUnitTestMode())
		{
			final boolean[] hasMethodReferenceOnInstance = new boolean[]{false};
			if (member instanceof PsiMethod method)
			{
				if (!ProgressManager.getInstance().runProcessWithProgressSynchronously(
					(Runnable)() ->
						hasMethodReferenceOnInstance[0] = !MethodReferencesSearch.search(method)
							.forEach(reference -> !(reference.getElement() instanceof PsiMethodReferenceExpression)),
					"Search for method references",
					true,
					project
				))
				{
					return;
				}
			}

			if (classRefsInMember.length > 0 || hasMethodReferenceOnInstance[0])
			{
				final PsiType type = JavaPsiFacade.getInstance(project).getElementFactory().createType(member.getContainingClass());
				//TODO: callback
				String[] nameSuggestions = JavaCodeStyleManager.getInstance(project)
					.suggestVariableName(VariableKind.PARAMETER, null, null, type).names;

				dialog = new MakeParameterizedStaticDialog(project, member, nameSuggestions, classRefsInMember);
			}
			else
			{
				dialog = new SimpleMakeStaticDialog(project, member);
			}

			dialog.show();
		}
	}

	@Nullable
	public static String validateTarget(PsiTypeParameterListOwner member)
	{
		PsiClass containingClass = member.getContainingClass();

		// Checking various preconditions
		if (member instanceof PsiMethod && ((PsiMethod) member).isConstructor())
		{
			return RefactoringLocalize.cannotPerformRefactoringWithReason(RefactoringLocalize.constructorCannotBeMadeStatic()).get();
		}

		if (member.getContainingClass() == null)
		{
			return RefactoringLocalize.cannotPerformRefactoringWithReason(RefactoringLocalize.thisMemberDoesNotSeemToBelongToAnyClass()).get();
		}

		if (member.isStatic())
		{
			return RefactoringLocalize.cannotPerformRefactoringWithReason(RefactoringLocalize.memberIsAlreadyStatic()).get();
		}

		if (member instanceof PsiMethod && member.isAbstract())
		{
			return RefactoringLocalize.cannotPerformRefactoringWithReason(RefactoringLocalize.cannotMakeAbstractMethodStatic()).get();
		}

		if (containingClass instanceof PsiAnonymousClass
			|| (containingClass.getContainingClass() != null && !containingClass.isStatic()))
		{
			return RefactoringLocalize.cannotPerformRefactoringWithReason(RefactoringLocalize.innerClassesCannotHaveStaticMembers()).get();
		}
		return null;
	}
}
