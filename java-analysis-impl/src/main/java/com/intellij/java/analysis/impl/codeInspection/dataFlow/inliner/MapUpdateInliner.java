/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.intellij.java.analysis.impl.codeInspection.dataFlow.inliner;

import com.intellij.java.analysis.impl.codeInspection.dataFlow.CFGBuilder;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.SpecialField;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.types.DfTypes;
import com.intellij.java.language.codeInsight.Nullability;
import com.intellij.java.language.psi.CommonClassNames;
import com.intellij.java.language.psi.PsiExpression;
import com.intellij.java.language.psi.PsiMethodCallExpression;
import com.intellij.java.language.psi.PsiType;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.ExpectedTypeUtils;
import jakarta.annotation.Nonnull;

import java.util.Objects;

public class MapUpdateInliner implements CallInliner {
    private static final CallMatcher MAP_COMPUTE =
        CallMatcher.instanceCall(CommonClassNames.JAVA_UTIL_MAP, "computeIfAbsent", "computeIfPresent", "compute")
            .parameterCount(2);
    private static final CallMatcher MAP_MERGE =
        CallMatcher.instanceCall(CommonClassNames.JAVA_UTIL_MAP, "merge").parameterCount(3);

    @Override
    public boolean tryInlineCall(@Nonnull CFGBuilder builder, @Nonnull PsiMethodCallExpression call) {
        if (MAP_COMPUTE.test(call)) {
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null) {
                return false;
            }
            PsiType type = call.getType();
            if (type == null) {
                return false;
            }
            PsiExpression[] args = call.getArgumentList().getExpressions();
            PsiExpression key = args[0];
            PsiExpression function = args[1];
            builder.pushExpression(qualifier) // stack: .. qualifier
                .pushExpression(key) // stack: .. qualifier; key
                .evaluateFunction(function);
            String name = Objects.requireNonNull(call.getMethodExpression().getReferenceName());
            switch (name) {
                case "computeIfAbsent":
                    inlineComputeIfAbsent(builder, function, type);
                    break;
                case "computeIfPresent":
                    inlineComputeIfPresent(builder, function, type);
                    break;
                case "compute":
                    inlineCompute(builder, function, type);
                    break;
                default:
                    throw new IllegalStateException("Unsupported name: " + name);
            }
            builder.resultOf(call);
            return true;
        }
        if (MAP_MERGE.test(call)) {
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null) {
                return false;
            }
            PsiType type = call.getType();
            if (type == null) {
                return false;
            }
            PsiExpression[] args = call.getArgumentList().getExpressions();
            PsiExpression key = args[0];
            PsiExpression value = args[1];
            PsiExpression function = args[2];
            builder.pushExpression(qualifier) // stack: .. qualifier
                .pushExpression(key) // stack: .. qualifier; key
                .pop() // stack: .. qualifier
                .pushExpression(value) // stack: .. qualifier; value
                .boxUnbox(value, ExpectedTypeUtils.findExpectedType(value, false))
                .evaluateFunction(function)
                .pushUnknown() // stack: .. qualifier; value; get() result
                .ifNotNull() // stack: .. qualifier; value
                .push(DfTypes.typedObject(type, Nullability.NOT_NULL)) // stack: .. qualifier; value; get() result
                .swap() // stack: .. qualifier; get() result; value
                .invokeFunction(2, function) // stack: .. qualifier; mapping result
                .end()
                .chain(b -> flushSize(b))
                .resultOf(call);
            return true;
        }
        return false;
    }

    private static void flushSize(CFGBuilder builder) {
        builder.swap().unwrap(SpecialField.COLLECTION_SIZE).pushUnknown().assign().pop();
    }

    private static void inlineComputeIfAbsent(@Nonnull CFGBuilder builder, PsiExpression function, PsiType type) {
        builder.pushUnknown() // stack: .. qualifier; key; get() result
            .ifNull() // stack: .. qualifier; key
            .invokeFunction(1, function) // stack: .. qualifier; mapping_result
            .chain(MapUpdateInliner::flushSize)
            .elseBranch()
            .splice(2)
            .push(DfTypes.typedObject(type, Nullability.NOT_NULL))
            .end();
    }

    private static void inlineComputeIfPresent(@Nonnull CFGBuilder builder, PsiExpression function, PsiType type) {
        builder.pushUnknown() // stack: .. qualifier; key; get() result
            .ifNotNull() // stack: .. qualifier; key
            .push(DfTypes.typedObject(type, Nullability.NOT_NULL))
            .invokeFunction(2, function) // stack: .. qualifier; mapping result
            .chain(MapUpdateInliner::flushSize)
            .elseBranch()
            .splice(2)
            .pushNull()
            .end();
    }

    private static void inlineCompute(@Nonnull CFGBuilder builder, PsiExpression function, PsiType type) {
        builder.push(DfTypes.typedObject(type, Nullability.NULLABLE))
            .invokeFunction(2, function) // stack: .. qualifier; mapping result
            .chain(MapUpdateInliner::flushSize);
    }
}
