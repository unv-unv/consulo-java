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
package com.intellij.java.debugger.impl.engine.evaluation.expression;

import com.intellij.java.debugger.engine.evaluation.EvaluateException;
import com.intellij.java.debugger.engine.evaluation.EvaluateExceptionUtil;
import com.intellij.java.debugger.impl.engine.evaluation.EvaluationContextImpl;
import com.intellij.java.debugger.engine.evaluation.expression.Modifier;
import consulo.internal.com.sun.jdi.BooleanValue;

/**
 * @author lex
 */
public class IfStatementEvaluator implements Evaluator {
  private final Evaluator myConditionEvaluator;
  private final Evaluator myThenEvaluator;
  private final Evaluator myElseEvaluator;

  private Modifier myModifier;

  public IfStatementEvaluator(Evaluator conditionEvaluator, Evaluator thenEvaluator, Evaluator elseEvaluator) {
    myConditionEvaluator = DisableGC.create(conditionEvaluator);
    myThenEvaluator = DisableGC.create(thenEvaluator);
    myElseEvaluator = elseEvaluator == null ? null : DisableGC.create(elseEvaluator);
  }

  public Modifier getModifier() {
    return myModifier;
  }

  public Object evaluate(EvaluationContextImpl context) throws EvaluateException {
    Object value = myConditionEvaluator.evaluate(context);
    if(!(value instanceof BooleanValue)) {
      throw EvaluateExceptionUtil.BOOLEAN_EXPECTED;
    } else {
      if(((BooleanValue)value).booleanValue()) {
        value = myThenEvaluator.evaluate(context);
        myModifier = myThenEvaluator.getModifier();
      }
      else {
        if(myElseEvaluator != null) {
          value = myElseEvaluator.evaluate(context);
          myModifier = myElseEvaluator.getModifier();
        } else {
          value = context.getDebugProcess().getVirtualMachineProxy().mirrorOf();
          myModifier = null;
        }
      }
    }
    return value;
  }

}
