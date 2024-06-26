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
package com.intellij.java.compiler.impl.cache;

import com.intellij.java.language.impl.JavaFileType;
import consulo.project.Project;
import consulo.util.lang.Pair;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiReference;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.search.ReferencesSearch;
import consulo.application.util.query.Query;
import consulo.util.collection.ContainerUtil;

import java.util.Collection;
import java.util.Map;

/**
 * @author Eugene Zhuravlev
 *         Date: Oct 15
 * @author 2003
 */
public class CachingSearcher {
  private final Project myProject;
  private final Map<Pair<PsiElement, Boolean>, Collection<PsiReference>> myElementToReferencersMap = ContainerUtil.createSoftMap();

  public CachingSearcher(Project project) {
    myProject = project;
  }

  public Collection<PsiReference> findReferences(PsiElement element, final boolean ignoreAccessScope) {
    final Pair<PsiElement, Boolean> key = Pair.create(element, ignoreAccessScope? Boolean.TRUE : Boolean.FALSE);
    Collection<PsiReference> psiReferences = myElementToReferencersMap.get(key);
    if (psiReferences == null) {
      GlobalSearchScope searchScope = GlobalSearchScope.projectScope(myProject);
      searchScope = GlobalSearchScope.getScopeRestrictedByFileTypes(searchScope, JavaFileType.INSTANCE);
      final Query<PsiReference> query = ReferencesSearch.search(element, searchScope, ignoreAccessScope);
      psiReferences = query.findAll();
      myElementToReferencersMap.put(key, psiReferences);
    }
    return psiReferences;
  }

}
