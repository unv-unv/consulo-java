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
 * User: max
 * Date: Feb 7, 2002
 * Time: 1:11:08 PM
 * To change template for new class use 
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInspection.dataFlow.instructions;

import static com.intellij.psi.JavaTokenType.EQEQ;
import static com.intellij.psi.JavaTokenType.GE;
import static com.intellij.psi.JavaTokenType.GT;
import static com.intellij.psi.JavaTokenType.INSTANCEOF_KEYWORD;
import static com.intellij.psi.JavaTokenType.LE;
import static com.intellij.psi.JavaTokenType.LT;
import static com.intellij.psi.JavaTokenType.NE;
import static com.intellij.psi.JavaTokenType.PLUS;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.codeInspection.dataFlow.DataFlowRunner;
import com.intellij.codeInspection.dataFlow.DfaInstructionState;
import com.intellij.codeInspection.dataFlow.DfaMemoryState;
import com.intellij.codeInspection.dataFlow.InstructionVisitor;
import com.intellij.codeInspection.dataFlow.Nullness;
import com.intellij.codeInspection.dataFlow.value.DfaValue;
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

public class BinopInstruction extends BranchingInstruction
{
	private static final TokenSet ourSignificantOperations = TokenSet.create(EQEQ, NE, LT, GT, LE, GE, INSTANCEOF_KEYWORD, PLUS);
	private final IElementType myOperationSign;
	private final Project myProject;

	public BinopInstruction(IElementType opSign, @Nullable PsiElement psiAnchor, @NotNull Project project)
	{
		super(psiAnchor);
		myProject = project;
		myOperationSign = ourSignificantOperations.contains(opSign) ? opSign : null;
	}

	@Override
	public DfaInstructionState[] accept(DataFlowRunner runner, DfaMemoryState stateBefore, InstructionVisitor visitor)
	{
		return visitor.visitBinop(this, runner, stateBefore);
	}

	public DfaValue getNonNullStringValue(final DfaValueFactory factory)
	{
		PsiElement anchor = getPsiAnchor();
		Project project = myProject;
		PsiClassType string = PsiType.getJavaLangString(PsiManager.getInstance(project), anchor == null ? GlobalSearchScope.allScope(project) :
				anchor.getResolveScope());
		return factory.createTypeValue(string, Nullness.NOT_NULL);
	}

	@Override
	public String toString()
	{
		return "BINOP " + myOperationSign;
	}

	public IElementType getOperationSign()
	{
		return myOperationSign;
	}
}