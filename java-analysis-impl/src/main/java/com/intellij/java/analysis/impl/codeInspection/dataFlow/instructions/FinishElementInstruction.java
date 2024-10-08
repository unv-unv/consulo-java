/*
 * Copyright 2013-2017 consulo.io
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
package com.intellij.java.analysis.impl.codeInspection.dataFlow.instructions;

import com.intellij.java.analysis.impl.codeInspection.dataFlow.DataFlowRunner;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.DfaInstructionState;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.DfaMemoryState;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.InstructionVisitor;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.value.DfaVariableValue;
import consulo.language.psi.PsiElement;

import java.util.HashSet;
import java.util.Set;

/**
 * @author peter
 */
public class FinishElementInstruction extends Instruction {
	private final Set<DfaVariableValue> myVarsToFlush = new HashSet<>();
  private final PsiElement myElement;

  public FinishElementInstruction(PsiElement element) {
    myElement = element;
  }

  @Override
  public DfaInstructionState[] accept(DataFlowRunner runner, DfaMemoryState state, InstructionVisitor visitor) {
    if (!myVarsToFlush.isEmpty()) {
      for (DfaVariableValue value : myVarsToFlush) {
        state.flushVariable(value);
      }
    }
    return nextInstruction(runner, state);
  }

  @Override
  public String toString() {
    return "Finish " + myElement + "; flushing " + myVarsToFlush;
  }

  public Set<DfaVariableValue> getVarsToFlush() {
    return myVarsToFlush;
  }
}
