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
package com.intellij.java.impl.cyclicDependencies.ui;

import consulo.ide.impl.idea.packageDependencies.ui.PackageDependenciesNode;
import consulo.language.editor.scope.localize.AnalysisScopeLocalize;
import consulo.project.Project;

/**
 * User: anna
 * Date: Jan 31, 2005
 */
public class CycleNode extends PackageDependenciesNode{
  public CycleNode(Project project) {
    super(project);
  }

  public String toString() {
    return AnalysisScopeLocalize.cyclicDependenciesTreeCycleNodeText().get();
  }
}
