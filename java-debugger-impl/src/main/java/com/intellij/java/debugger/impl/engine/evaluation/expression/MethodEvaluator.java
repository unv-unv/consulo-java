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

/*
 * Class MethodEvaluator
 * @author Jeka
 */
package com.intellij.java.debugger.impl.engine.evaluation.expression;

import com.intellij.java.debugger.DebuggerBundle;
import com.intellij.java.debugger.engine.DebuggerUtils;
import com.intellij.java.debugger.engine.evaluation.EvaluateException;
import com.intellij.java.debugger.engine.evaluation.EvaluateExceptionUtil;
import com.intellij.java.debugger.engine.evaluation.expression.Modifier;
import com.intellij.java.debugger.impl.DebuggerUtilsEx;
import com.intellij.java.debugger.impl.engine.DebugProcessImpl;
import com.intellij.java.debugger.impl.engine.JVMName;
import com.intellij.java.debugger.impl.engine.evaluation.EvaluateRuntimeException;
import com.intellij.java.debugger.impl.engine.evaluation.EvaluationContextImpl;
import consulo.internal.com.sun.jdi.ClassType;
import consulo.internal.com.sun.jdi.Method;
import consulo.internal.com.sun.jdi.ObjectReference;
import consulo.internal.com.sun.jdi.ReferenceType;
import consulo.logging.Logger;

import java.util.ArrayList;
import java.util.List;

public class MethodEvaluator implements Evaluator {
    private static final Logger LOG = Logger.getInstance(MethodEvaluator.class);
    private final JVMName myClassName;
    private final JVMName myMethodSignature;
    private final String myMethodName;
    private final Evaluator[] myArgumentEvaluators;
    private final Evaluator myObjectEvaluator;
    private final boolean myCheckDefaultInterfaceMethod;
    private final boolean myMustBeVararg;

    public MethodEvaluator(Evaluator objectEvaluator, JVMName className, String methodName, JVMName signature, Evaluator[] argumentEvaluators) {
        this(objectEvaluator, className, methodName, signature, argumentEvaluators, false, false);
    }

    public MethodEvaluator(Evaluator objectEvaluator,
                           JVMName className,
                           String methodName,
                           JVMName signature,
                           Evaluator[] argumentEvaluators,
                           boolean checkDefaultInterfaceMethod,
                           boolean mustBeVararg) {
        myObjectEvaluator = DisableGC.create(objectEvaluator);
        myClassName = className;
        myMethodName = methodName;
        myMethodSignature = signature;
        myArgumentEvaluators = argumentEvaluators;
        myCheckDefaultInterfaceMethod = checkDefaultInterfaceMethod;
        myMustBeVararg = mustBeVararg;
    }

    @Override
    public Modifier getModifier() {
        return null;
    }

    @Override
    public Object evaluate(EvaluationContextImpl context) throws EvaluateException {
        if (!context.getDebugProcess().isAttached()) {
            return null;
        }
        DebugProcessImpl debugProcess = context.getDebugProcess();

        final boolean requiresSuperObject = myObjectEvaluator instanceof SuperEvaluator || (myObjectEvaluator instanceof DisableGC && ((DisableGC) myObjectEvaluator).getDelegate() instanceof
            SuperEvaluator);

        final Object object = myObjectEvaluator.evaluate(context);
        if (LOG.isDebugEnabled()) {
            LOG.debug("MethodEvaluator: object = " + object);
        }
        if (object == null) {
            throw EvaluateExceptionUtil.createEvaluateException(new NullPointerException());
        }
        if (!(object instanceof ObjectReference || object instanceof ClassType)) {
            throw EvaluateExceptionUtil.createEvaluateException(DebuggerBundle.message("evaluation.error.evaluating.method", myMethodName));
        }
        List args = new ArrayList(myArgumentEvaluators.length);
        for (Evaluator evaluator : myArgumentEvaluators) {
            args.add(evaluator.evaluate(context));
        }
        try {
            ReferenceType referenceType = null;

            if (object instanceof ObjectReference) {
                // it seems that if we have an object of the class, the class must be ready, so no need to use findClass here
                referenceType = ((ObjectReference) object).referenceType();
            }
            else if (object instanceof ClassType) {
                final ClassType qualifierType = (ClassType) object;
                referenceType = debugProcess.findClass(context, qualifierType.name(), context.getClassLoader());
            }
            else {
                final String className = myClassName != null ? myClassName.getName(debugProcess) : null;
                if (className != null) {
                    referenceType = debugProcess.findClass(context, className, context.getClassLoader());
                }
            }

            if (referenceType == null) {
                throw new EvaluateRuntimeException(EvaluateExceptionUtil.createEvaluateException(DebuggerBundle.message("evaluation.error.cannot.evaluate.qualifier", myMethodName)));
            }
            final String signature = myMethodSignature != null ? myMethodSignature.getName(debugProcess) : null;
            final String methodName = DebuggerUtilsEx.methodName(referenceType.name(), myMethodName, signature);
            if (object instanceof ClassType) {
                if (referenceType instanceof ClassType) {
                    Method jdiMethod;
                    if (myMethodSignature != null) {
                        jdiMethod = ((ClassType) referenceType).concreteMethodByName(myMethodName, myMethodSignature.getName(debugProcess));
                    }
                    else {
                        List list = referenceType.methodsByName(myMethodName);
                        jdiMethod = (Method) (list.size() > 0 ? list.get(0) : null);
                    }
                    if (jdiMethod != null && jdiMethod.isStatic()) {
                        return debugProcess.invokeMethod(context, (ClassType) referenceType, jdiMethod, args);
                    }
                }
                throw EvaluateExceptionUtil.createEvaluateException(DebuggerBundle.message("evaluation.error.no.static.method", methodName));
            }
            // object should be an ObjectReference
            final ObjectReference objRef = (ObjectReference) object;
            ReferenceType _refType = referenceType;
            if (requiresSuperObject && (referenceType instanceof ClassType)) {
                _refType = ((ClassType) referenceType).superclass();
            }
            Method jdiMethod = DebuggerUtils.findMethod(_refType, myMethodName, signature);
            if (signature == null) {
                // we know nothing about expected method's signature, so trying to match my method name and parameter count
                // dummy matching, may be improved with types matching later
                // IMPORTANT! using argumentTypeNames() instead of argumentTypes() to avoid type resolution inside JDI, which may be time-consuming
                if (jdiMethod == null || jdiMethod.argumentTypeNames().size() != args.size()) {
                    for (Method method : _refType.methodsByName(myMethodName)) {
                        if (method.argumentTypeNames().size() == args.size()) {
                            jdiMethod = method;
                            break;
                        }
                    }
                }
            }
            else if (myMustBeVararg && jdiMethod != null && !jdiMethod.isVarArgs() && jdiMethod.isBridge()) {
                // see IDEA-129869, avoid bridge methods for varargs
                int retTypePos = signature.lastIndexOf(")");
                if (retTypePos >= 0) {
                    String signatureNoRetType = signature.substring(0, retTypePos + 1);
                    for (Method method : _refType.visibleMethods()) {
                        if (method.name().equals(myMethodName) && method.signature().startsWith(signatureNoRetType) && !method.isBridge() && !method.isAbstract()) {
                            jdiMethod = method;
                            break;
                        }
                    }
                }
            }
            if (jdiMethod == null) {
                throw EvaluateExceptionUtil.createEvaluateException(DebuggerBundle.message("evaluation.error.no.instance.method", methodName));
            }
            if (requiresSuperObject) {
                return debugProcess.invokeInstanceMethod(context, objRef, jdiMethod, args, ObjectReference.INVOKE_NONVIRTUAL);
            }

            return debugProcess.invokeMethod(context, objRef, jdiMethod, args);
        }
        catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(e);
            }
            throw EvaluateExceptionUtil.createEvaluateException(e);
        }
    }

    @Override
    public String toString() {
        return "call " + myMethodName;
    }
}
