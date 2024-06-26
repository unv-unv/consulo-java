/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.java.indexing.search.searches;

import com.intellij.java.language.psi.PsiMethod;
import consulo.application.util.query.ExtensibleQueryFactory;
import consulo.application.util.query.MergeQuery;
import consulo.application.util.query.Query;
import consulo.application.util.query.UniqueResultsQuery;
import consulo.content.scope.SearchScope;
import consulo.language.psi.PsiReference;
import consulo.language.psi.PsiUtilCore;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.search.*;
import consulo.project.Project;
import consulo.project.util.query.DumbAwareSearchParameters;
import consulo.util.collection.ContainerUtil;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * @author max
 */
public class MethodReferencesSearch extends ExtensibleQueryFactory<PsiReference, MethodReferencesSearch.SearchParameters> {
  public static final MethodReferencesSearch INSTANCE = new MethodReferencesSearch();

  public static class SearchParameters implements DumbAwareSearchParameters {
    private final PsiMethod myMethod;
    private final Project myProject;
    private final SearchScope myScope;
    private final boolean myStrictSignatureSearch;
    private final SearchRequestCollector myOptimizer;
    private final boolean isSharedOptimizer;

    public SearchParameters(@Nonnull PsiMethod method,
                            @Nonnull SearchScope scope,
                            boolean strictSignatureSearch,
                            @Nullable SearchRequestCollector optimizer) {
      myMethod = method;
      myScope = scope;
      myStrictSignatureSearch = strictSignatureSearch;
      isSharedOptimizer = optimizer != null;
      myOptimizer = optimizer != null ? optimizer : new SearchRequestCollector(new SearchSession());
      myProject = PsiUtilCore.getProjectInReadAction(method);
    }

    public SearchParameters(@Nonnull PsiMethod method, @Nonnull SearchScope scope, final boolean strict) {
      this(method, scope, strict, null);
    }

    @Nonnull
    public Project getProject() {
      return myProject;
    }

    @Nonnull
    public PsiMethod getMethod() {
      return myMethod;
    }

    public boolean isStrictSignatureSearch() {
      return myStrictSignatureSearch;
    }

    public SearchRequestCollector getOptimizer() {
      return myOptimizer;
    }

    /**
     * @return the user-visible search scope, most often "Project Files" or "Project and Libraries".
     * Searchers most likely need to use {@link #getEffectiveSearchScope()}.
     */
    public SearchScope getScopeDeterminedByUser() {
      return myScope;
    }


    /**
     * @return Same as {@link #getScopeDeterminedByUser()}. Searchers most likely need to use {@link
     * #getEffectiveSearchScope()}.
     */
    @Deprecated
    @Nonnull
    public SearchScope getScope() {
      return getScopeDeterminedByUser();
    }

    @Nonnull
    public SearchScope getEffectiveSearchScope() {
      SearchScope accessScope = PsiSearchHelper.getInstance(myMethod.getProject()).getUseScope(myMethod);
      return myScope.intersectWith(accessScope);
    }
  }

  private MethodReferencesSearch() {
    super(MethodReferencesSearchExecutor.class);
  }

  public static Query<PsiReference> search(@Nonnull PsiMethod method,
                                           SearchScope scope,
                                           final boolean strictSignatureSearch) {
    return search(new SearchParameters(method, scope, strictSignatureSearch));
  }

  public static void searchOptimized(final PsiMethod method,
                                     SearchScope scope,
                                     final boolean strictSignatureSearch,
                                     @Nonnull SearchRequestCollector collector,
                                     final Predicate<PsiReference> processor) {
    searchOptimized(method, scope, strictSignatureSearch, collector, false, (psiReference, collector1) -> processor.test(psiReference));
  }

  public static void searchOptimized(final PsiMethod method,
                                     SearchScope scope,
                                     final boolean strictSignatureSearch,
                                     SearchRequestCollector collector,
                                     final boolean inReadAction,
                                     BiPredicate<PsiReference, SearchRequestCollector> processor) {
    final SearchRequestCollector nested = new SearchRequestCollector(collector.getSearchSession());
    collector.searchQuery(new QuerySearchRequest(search(new SearchParameters(method, scope, strictSignatureSearch,
        nested)), nested, inReadAction, processor));
  }

  public static Query<PsiReference> search(final SearchParameters parameters) {
    final Query<PsiReference> result = INSTANCE.createQuery(parameters);
    if (parameters.isSharedOptimizer) {
      return uniqueResults(result);
    }

    final SearchRequestCollector requests = parameters.getOptimizer();

    Project project = PsiUtilCore.getProjectInReadAction(parameters.getMethod());
    return uniqueResults(new MergeQuery<PsiReference>(result, new SearchRequestQuery(project, requests)));
  }

  public static Query<PsiReference> search(final PsiMethod method, final boolean strictSignatureSearch) {
    return search(method, GlobalSearchScope.allScope(PsiUtilCore.getProjectInReadAction(method)),
        strictSignatureSearch);
  }

  public static Query<PsiReference> search(final PsiMethod method) {
    return search(method, true);
  }

  private static UniqueResultsQuery<PsiReference, ReferenceDescriptor> uniqueResults(@Nonnull Query<PsiReference>
                                                                                         composite) {
    return new UniqueResultsQuery<PsiReference, ReferenceDescriptor>(composite, ContainerUtil.<ReferenceDescriptor>canonicalStrategy(), ReferenceDescriptor.MAPPER);
  }
}