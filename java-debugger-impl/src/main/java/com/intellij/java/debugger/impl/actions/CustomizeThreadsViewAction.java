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
package com.intellij.java.debugger.impl.actions;

import com.intellij.java.debugger.impl.settings.ThreadsViewSettings;
import com.intellij.java.debugger.localize.JavaDebuggerLocalize;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.java.debugger.impl.settings.ThreadsViewConfigurable;
import consulo.platform.base.localize.ActionLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import jakarta.annotation.Nonnull;

/**
 * @author lex
 * @since 2003-09-26
 */
public class CustomizeThreadsViewAction extends DebuggerAction {
    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent e) {
        Project project = e.getData(Project.KEY);

        ShowSettingsUtil.getInstance().editConfigurable(
            JavaDebuggerLocalize.threadsViewConfigurableDisplayName().get(),
            project,
            new ThreadsViewConfigurable(ThreadsViewSettings::getInstance)
        );
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        e.getPresentation().setVisible(true);
        e.getPresentation().setTextValue(ActionLocalize.actionDebuggerCustomizethreadsviewText());
    }
}
