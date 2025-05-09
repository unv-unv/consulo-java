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
package com.intellij.java.impl.psi.impl.search;

import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.psi.PsiJavaCodeReferenceElement;
import com.intellij.java.language.psi.PsiLocalVariable;
import com.intellij.java.language.psi.PsiParameter;
import com.intellij.java.language.psi.PsiVariable;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.content.scope.SearchScope;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiReference;
import consulo.language.psi.resolve.PsiElementProcessor;
import consulo.language.psi.scope.LocalSearchScope;
import consulo.language.psi.search.ReferencesSearch;
import consulo.language.psi.search.ReferencesSearchQueryExecutor;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.project.util.query.QueryExecutorBase;
import jakarta.annotation.Nonnull;

import java.util.function.Predicate;

/**
 * Looks for references to local variable or method parameter in invalid (incomplete) code.
 */
@ExtensionImpl
public class VariableInIncompleteCodeSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> implements ReferencesSearchQueryExecutor {
    public VariableInIncompleteCodeSearcher() {
        super(true);
    }

    @Override
    @RequiredReadAction
    public void processQuery(@Nonnull ReferencesSearch.SearchParameters p, @Nonnull Predicate<? super PsiReference> consumer) {
        PsiElement refElement = p.getElementToSearch();
        if (!refElement.isValid() || !(refElement instanceof PsiLocalVariable || refElement instanceof PsiParameter)) {
            return;
        }

        String name = ((PsiVariable)refElement).getName();
        if (name == null) {
            return;
        }

        SearchScope scope = p.getEffectiveSearchScope();
        if (!(scope instanceof LocalSearchScope localSearchScope)) {
            return;
        }

        PsiElement[] elements = localSearchScope.getScope();
        if (elements == null || elements.length == 0) {
            return;
        }

        PsiElementProcessor processor = element -> {
            if (element instanceof PsiJavaCodeReferenceElement ref
                && !ref.isQualified() && name.equals(ref.getText())
                && ref.resolve() == null && ref.advancedResolve(true).getElement() == refElement) {
                consumer.test(ref);
            }
            return true;
        };

        for (PsiElement element : elements) {
            if (element.getLanguage().isKindOf(JavaLanguage.INSTANCE)) {
                PsiTreeUtil.processElements(element, processor);
            }
        }
    }
}
