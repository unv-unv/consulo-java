/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.java.debugger.impl.actions;

import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.language.psi.PsiSubstitutor;
import com.intellij.java.language.psi.util.PsiFormatUtil;
import com.intellij.java.language.psi.util.PsiFormatUtilBase;
import consulo.annotation.access.RequiredReadAction;
import consulo.language.icon.IconDescriptorUpdaters;
import consulo.language.psi.PsiElement;
import consulo.ui.image.Image;
import consulo.util.lang.Range;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * @author Eugene Zhuravlev
 * @since 2013-10-25
 */
public class MethodSmartStepTarget extends SmartStepTarget {
    private final PsiMethod myMethod;

    public MethodSmartStepTarget(
        @Nonnull PsiMethod method,
        @Nullable String label,
        @Nullable PsiElement highlightElement,
        boolean needBreakpointRequest,
        Range<Integer> lines
    ) {
        super(label, highlightElement, needBreakpointRequest, lines);
        myMethod = method;
    }

    @Nullable
    @Override
    @RequiredReadAction
    public Image getIcon() {
        return IconDescriptorUpdaters.getIcon(myMethod, 0);
    }

    @Nonnull
    @Override
    public String getPresentation() {
        String label = getLabel();
        String formatted = PsiFormatUtil.formatMethod(
            myMethod,
            PsiSubstitutor.EMPTY,
            PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_PARAMETERS,
            PsiFormatUtilBase.SHOW_TYPE,
            999
        );
        return label != null ? label + formatted : formatted;
    }

    @Nonnull
    public PsiMethod getMethod() {
        return myMethod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MethodSmartStepTarget that = (MethodSmartStepTarget)o;

        return myMethod.equals(that.myMethod);
    }

    @Override
    public int hashCode() {
        return myMethod.hashCode();
    }
}
