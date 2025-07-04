/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.java.impl.refactoring.introduceParameter;

import com.intellij.java.impl.refactoring.util.usageInfo.DefaultConstructorImplicitUsageInfo;
import com.intellij.java.impl.refactoring.util.usageInfo.NoConstructorClassUsageInfo;
import com.intellij.java.language.psi.PsiMethod;
import consulo.annotation.access.RequiredReadAction;
import consulo.application.Application;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiManager;
import consulo.language.util.IncorrectOperationException;
import consulo.usage.UsageInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Maxim.Medvedev
 */
public class IntroduceParameterUtil {
    private IntroduceParameterUtil() {
    }

    public static boolean insideMethodToBeReplaced(PsiElement methodUsage, PsiMethod methodToReplaceIn) {
        PsiElement parent = methodUsage.getParent();
        while (parent != null) {
            if (parent.equals(methodToReplaceIn)) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    public static boolean isMethodUsage(UsageInfo usageInfo) {
        return Application.get().getExtensionPoint(IntroduceParameterMethodUsagesProcessor.class)
            .anyMatchSafe(processor -> processor.isMethodUsage(usageInfo));
    }

    public static void addSuperCall(UsageInfo usage, UsageInfo[] usages, IntroduceParameterData data)
        throws IncorrectOperationException {
        Application.get().getExtensionPoint(IntroduceParameterMethodUsagesProcessor.class)
            .allMatchSafe(processor -> processor.processAddSuperCall(data, usage, usages));
    }

    public static void addDefaultConstructor(UsageInfo usage, UsageInfo[] usages, IntroduceParameterData data)
        throws IncorrectOperationException {
        Application.get().getExtensionPoint(IntroduceParameterMethodUsagesProcessor.class)
            .allMatchSafe(processor -> processor.processAddDefaultConstructor(data, usage, usages));
    }

    public static void changeExternalUsage(UsageInfo usage, UsageInfo[] usages, IntroduceParameterData data)
        throws IncorrectOperationException {
        Application.get().getExtensionPoint(IntroduceParameterMethodUsagesProcessor.class)
            .allMatchSafe(processor -> processor.processChangeMethodUsage(data, usage, usages));
    }

    public static void changeMethodSignatureAndResolveFieldConflicts(
        UsageInfo usage,
        UsageInfo[] usages,
        IntroduceParameterData data
    ) throws IncorrectOperationException {
        Application.get().getExtensionPoint(IntroduceParameterMethodUsagesProcessor.class)
            .allMatchSafe(processor -> processor.processChangeMethodSignature(data, usage, usages));
    }

    @RequiredReadAction
    public static void processUsages(UsageInfo[] usages, IntroduceParameterData data) {
        PsiManager manager = PsiManager.getInstance(data.getProject());

        List<UsageInfo> methodUsages = new ArrayList<>();

        for (UsageInfo usage : usages) {
            if (usage instanceof InternalUsageInfo) {
                continue;
            }

            if (usage instanceof DefaultConstructorImplicitUsageInfo) {
                addSuperCall(usage, usages, data);
            }
            else if (usage instanceof NoConstructorClassUsageInfo) {
                addDefaultConstructor(usage, usages, data);
            }
            else if (usage.getElement() instanceof PsiMethod method) {
                if (!manager.areElementsEquivalent(method, data.getMethodToReplaceIn())) {
                    methodUsages.add(usage);
                }
            }
            else if (!data.isGenerateDelegate()) {
                changeExternalUsage(usage, usages, data);
            }
        }

        for (UsageInfo usage : methodUsages) {
            changeMethodSignatureAndResolveFieldConflicts(usage, usages, data);
        }
    }

    @RequiredReadAction
    public static boolean isMethodInUsages(IntroduceParameterData data, PsiMethod method, UsageInfo[] usages) {
        PsiManager manager = PsiManager.getInstance(data.getProject());
        for (UsageInfo info : usages) {
            if (!(info instanceof DefaultConstructorImplicitUsageInfo) && manager.areElementsEquivalent(info.getElement(), method)) {
                return true;
            }
        }
        return false;
    }
}
