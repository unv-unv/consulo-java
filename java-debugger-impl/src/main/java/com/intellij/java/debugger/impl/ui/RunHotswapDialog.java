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
package com.intellij.java.debugger.impl.ui;

import com.intellij.java.debugger.DebuggerBundle;
import com.intellij.java.debugger.impl.DebuggerSession;
import com.intellij.java.debugger.impl.settings.DebuggerSettings;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.ex.awt.ElementsChooser;
import consulo.ui.ex.awt.IdeBorderFactory;
import consulo.ui.ex.awt.OptionsDialog;
import consulo.ui.ex.awt.UIUtil;
import consulo.ui.ex.awt.internal.laf.MultiLineLabelUI;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.ui.image.Image;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * User: lex
 * Date: Oct 6, 2003
 * Time: 5:58:17 PM
 */
public class RunHotswapDialog extends OptionsDialog {
  private final JPanel myPanel;
  private final ElementsChooser<SessionItem> myElementsChooser;
  private final boolean myDisplayHangWarning;

  public RunHotswapDialog(Project project, List<DebuggerSession> sessions, boolean displayHangWarning) {
    super(project);
    myDisplayHangWarning = displayHangWarning;
    myPanel = new JPanel(new BorderLayout());
    final List<SessionItem> items = new ArrayList<SessionItem>(sessions.size());
    for (DebuggerSession session : sessions) {
      items.add(new SessionItem(session));
    }
    Collections.sort(items, new Comparator<SessionItem>() {
      public int compare(SessionItem debuggerSession, SessionItem debuggerSession1) {
        return debuggerSession.getSession().getSessionName().compareTo(debuggerSession1.getSession().getSessionName());
      }
    });
    myElementsChooser = new ElementsChooser<SessionItem>(items, true);
    myPanel.setBorder(IdeBorderFactory.createEmptyBorder(10, 0, 5, 0));
    //myElementsChooser.setBorder(IdeBorderFactory.createEmptyBorder(5, 0, 0, 0));
    if (sessions.size() > 0) {
      myElementsChooser.selectElements(items.subList(0, 1));
    }
    myPanel.add(myElementsChooser, BorderLayout.CENTER);
    //myPanel.add(new JLabel("Choose debug sessions to reload classes:"), BorderLayout.NORTH);
    if (sessions.size() == 1) {
      setTitle(DebuggerBundle.message("hotswap.dialog.title.with.session", sessions.get(0).getSessionName()));
      myPanel.setVisible(false);
    }
    else {
      setTitle(DebuggerBundle.message("hotswap.dialog.title"));
    }
    setButtonsAlignment(SwingUtilities.CENTER);
    this.init();
  }

  protected boolean isToBeShown() {
    return DebuggerSettings.RUN_HOTSWAP_ASK.equals(DebuggerSettings.getInstance().RUN_HOTSWAP_AFTER_COMPILE);
  }

  protected void setToBeShown(boolean value, boolean onOk) {
    if (value) {
      DebuggerSettings.getInstance().RUN_HOTSWAP_AFTER_COMPILE = DebuggerSettings.RUN_HOTSWAP_ASK;
    }
    else {
      if (onOk) {
        DebuggerSettings.getInstance().RUN_HOTSWAP_AFTER_COMPILE = DebuggerSettings.RUN_HOTSWAP_ALWAYS;
      }
      else {
        DebuggerSettings.getInstance().RUN_HOTSWAP_AFTER_COMPILE = DebuggerSettings.RUN_HOTSWAP_NEVER;
      }
    }
  }

  protected boolean shouldSaveOptionsOnCancel() {
    return true;
  }

  @Nonnull
  protected Action[] createActions(){
    setOKButtonText(CommonLocalize.buttonYes().get());
    setCancelButtonText(CommonLocalize.buttonNo().get());
    return new Action[]{getOKAction(), getCancelAction()};
  }

  protected JComponent createNorthPanel() {
    JLabel label = new JLabel(DebuggerBundle.message("hotswap.dialog.run.prompt"));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(label, BorderLayout.CENTER);
    Image icon = UIUtil.getQuestionIcon();
    if (icon != null) {
      label.setIcon(TargetAWT.to(icon));
      label.setIconTextGap(7);
    }
    if (myDisplayHangWarning) {
      final JLabel warningLabel = new JLabel("WARNING! " + DebuggerBundle.message("hotswap.dialog.hang.warning"));
      warningLabel.setUI(new MultiLineLabelUI());
      panel.add(warningLabel, BorderLayout.SOUTH);
    }
    return panel;
  }

  protected JComponent createCenterPanel() {
    return myPanel;
  }

  public Collection<DebuggerSession> getSessionsToReload() {
    final List<SessionItem> markedElements = myElementsChooser.getMarkedElements();
    final List<DebuggerSession>  sessions = new ArrayList<>(markedElements.size());
    for (SessionItem item : markedElements) {
      sessions.add(item.getSession());
    }
    return sessions;
  }

  private static class SessionItem {
    private final DebuggerSession mySession;

    public SessionItem(DebuggerSession session) {
      mySession = session;
    }

    public DebuggerSession getSession() {
      return mySession;
    }

    public String toString() {
      return mySession.getSessionName();
    }
  }
}
