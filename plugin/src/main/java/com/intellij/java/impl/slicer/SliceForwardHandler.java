/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package com.intellij.java.impl.slicer;

import consulo.language.editor.scope.AnalysisScope;
import consulo.language.editor.ui.awt.scope.BaseAnalysisActionDialog;
import consulo.language.editor.ui.scope.AnalysisUIOptions;
import consulo.language.psi.PsiElement;
import consulo.module.Module;
import consulo.project.Project;

import javax.swing.*;

/**
 * @author cdr
 */
public class SliceForwardHandler extends SliceHandler {
  public SliceForwardHandler() {
    super(false);
  }

  @Override
  public SliceAnalysisParams askForParams(PsiElement element, boolean dataFlowToThis, SliceManager.StoredSettingsBean storedSettingsBean, String dialogTitle) {
    AnalysisScope analysisScope = new AnalysisScope(element.getContainingFile());
    Module module = element.getModule();
    String name = module == null ? null : module.getName();

    Project myProject = element.getProject();
    final SliceForwardForm form = new SliceForwardForm();
    form.init(storedSettingsBean.showDereferences);

    AnalysisUIOptions analysisUIOptions = new AnalysisUIOptions();
    analysisUIOptions.save(storedSettingsBean.analysisUIOptions);

    BaseAnalysisActionDialog dialog = new BaseAnalysisActionDialog(dialogTitle, "Analyze scope", myProject, analysisScope, name, true,
                                                                   analysisUIOptions,
                                                                   element) {
      @Override
      protected JComponent getAdditionalActionSettings(Project project) {
        return form.getComponent();
      }
    };
    dialog.show();
    if (!dialog.isOK()) return null;

    storedSettingsBean.analysisUIOptions.save(analysisUIOptions);
    storedSettingsBean.showDereferences  = form.isToShowDerefs();

    AnalysisScope scope = dialog.getScope(analysisScope);

    SliceAnalysisParams params = new SliceAnalysisParams();
    params.scope = scope;
    params.dataFlowToThis = dataFlowToThis;
    params.showInstanceDereferences = form.isToShowDerefs();
    return params;
  }
}