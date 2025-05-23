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
package com.intellij.java.impl.ide.hierarchy.call;

import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.psi.PsiMethod;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.dataContext.DataContext;
import consulo.ide.impl.idea.ide.hierarchy.CallHierarchyBrowserBase;
import consulo.language.Language;
import consulo.language.editor.hierarchy.CallHierarchyProvider;
import consulo.language.editor.hierarchy.HierarchyBrowser;
import consulo.language.psi.PsiElement;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.project.Project;

import jakarta.annotation.Nonnull;

/**
 * @author yole
 */
@ExtensionImpl
public class JavaCallHierarchyProvider implements CallHierarchyProvider {
    @Override
    public PsiElement getTarget(@Nonnull DataContext dataContext) {
        Project project = dataContext.getData(Project.KEY);
        if (project == null) {
            return null;
        }

        PsiElement element = dataContext.getData(PsiElement.KEY);
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }

    @Override
    @Nonnull
    public HierarchyBrowser createHierarchyBrowser(PsiElement target) {
        return new CallHierarchyBrowser(target.getProject(), (PsiMethod)target);
    }

    @Override
    @RequiredReadAction
    public void browserActivated(@Nonnull HierarchyBrowser hierarchyBrowser) {
        ((CallHierarchyBrowser)hierarchyBrowser).changeView(CallHierarchyBrowserBase.CALLER_TYPE);
    }

    @Nonnull
    @Override
    public Language getLanguage() {
        return JavaLanguage.INSTANCE;
    }
}
