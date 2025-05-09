/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.java.debugger.impl.settings;

import com.intellij.java.debugger.DebuggerBundle;
import com.intellij.java.debugger.impl.ui.tree.render.CompoundNodeRenderer;
import com.intellij.java.debugger.impl.ui.tree.render.NodeRenderer;
import consulo.application.AllIcons;
import consulo.configurable.IdeaConfigurableUi;
import consulo.disposer.Disposable;
import consulo.ui.ex.action.ActionToolbarPosition;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.awt.*;
import consulo.ui.ex.awt.event.DocumentAdapter;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class UserRenderersConfigurable extends JPanel implements IdeaConfigurableUi<NodeRendererSettings>, Disposable {
    private final JPanel myNameFieldPanel;
    private final JTextField myNameField;
    private final ElementsChooser<NodeRenderer> myRendererChooser;
    private NodeRenderer myCurrentRenderer = null;
    private final CompoundRendererConfigurable myRendererDataConfigurable = new CompoundRendererConfigurable(this);

    public UserRenderersConfigurable() {
        super(new BorderLayout(JBUI.scale(4), 0));

        myRendererChooser = new ElementsChooser<>(true);
        setupRenderersList();

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator((JTable) myRendererChooser.getComponent());
        decorator.setToolbarPosition(ActionToolbarPosition.TOP);
        decorator.setAddAction(new AddAction());
        decorator.setRemoveAction(new RemoveAction());
        decorator.setMoveUpAction(new MoveAction(true));
        decorator.setMoveDownAction(new MoveAction(false));
        decorator.addExtraAction(new CopyAction());

        myNameField = new JTextField();
        myNameFieldPanel = new JPanel(new BorderLayout());
        myNameFieldPanel.add(new JLabel(DebuggerBundle.message("label.user.renderers.configurable.renderer.name")), BorderLayout.WEST);
        myNameFieldPanel.add(myNameField, BorderLayout.CENTER);
        myNameFieldPanel.setVisible(false);

        final JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(myNameFieldPanel, BorderLayout.NORTH);
        center.add(myRendererDataConfigurable, BorderLayout.CENTER);

        myNameField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                if (myCurrentRenderer != null) {
                    myCurrentRenderer.setName(myNameField.getText());
                    myRendererChooser.refresh(myCurrentRenderer);
                }
            }
        });

        Splitter splitter = new Splitter(false);
        splitter.setProportion(0.3f);
        splitter.setFirstComponent(decorator.createPanel());
        splitter.setSecondComponent(center);
        add(splitter, BorderLayout.CENTER);
    }

    @Override
    public void dispose() {
    }

    @Override
    @Nonnull
    public JComponent getComponent(@Nonnull Disposable parentDisposable) {
        return this;
    }

    private void setupRenderersList() {
        myRendererChooser.getEmptyText().setText(DebuggerBundle.message("text.user.renderers.configurable.no.renderers"));

        myRendererChooser.addElementsMarkListener((ElementsChooser.ElementsMarkListener<NodeRenderer>) NodeRenderer::setEnabled);
        myRendererChooser.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateCurrentRenderer(myRendererChooser.getSelectedElements());
            }
        });
    }

    private void updateCurrentRenderer(List<NodeRenderer> selectedElements) {
        if (selectedElements.size() != 1) {
            // multi selection
            setCurrentRenderer(null);
        }
        else {
            setCurrentRenderer(selectedElements.get(0));
        }
    }

    private void setCurrentRenderer(NodeRenderer renderer) {
        if (myCurrentRenderer == renderer) {
            return;
        }
        if (myRendererDataConfigurable.isModified()) {
            myRendererDataConfigurable.apply();
        }
        myCurrentRenderer = renderer;
        if (renderer != null) {
            myNameFieldPanel.setVisible(true);
            myNameField.setText(renderer.getName());
        }
        else {
            myNameFieldPanel.setVisible(false);
            myNameField.setText("");
        }
        myRendererDataConfigurable.setRenderer(renderer);
    }

    @Override
    public void apply(@Nonnull NodeRendererSettings settings) {
        myRendererDataConfigurable.apply();
        flushTo(settings.getCustomRenderers());

        settings.fireRenderersChanged();
    }

    private void flushTo(final RendererConfiguration rendererConfiguration) {
        final int count = myRendererChooser.getElementCount();
        final List<NodeRenderer> renderers = new ArrayList<NodeRenderer>(count);
        for (int idx = 0; idx < count; idx++) {
            renderers.add(myRendererChooser.getElementAt(idx));
        }
        rendererConfiguration.setRenderers(renderers);
    }

    @Override
    public boolean isModified(@Nonnull NodeRendererSettings settings) {
        if (myRendererDataConfigurable.isModified()) {
            return true;
        }
        final RendererConfiguration rendererConfiguration = settings.getCustomRenderers();
        if (myRendererChooser.getElementCount() != rendererConfiguration.getRendererCount()) {
            return true;
        }
        final RendererConfiguration uiConfiguration = new RendererConfiguration(settings);
        flushTo(uiConfiguration);
        return !uiConfiguration.equals(rendererConfiguration);
    }

    @Override
    public void reset(@Nonnull NodeRendererSettings settings) {
        myRendererChooser.removeAllElements();
        final RendererConfiguration rendererConfiguration = settings.getCustomRenderers();
        final ArrayList<NodeRenderer> elementsToSelect = new ArrayList<NodeRenderer>(1);
        rendererConfiguration.iterateRenderers(new Predicate<NodeRenderer>() {
            @Override
            public boolean test(final NodeRenderer renderer) {
                final NodeRenderer clonedRenderer = (NodeRenderer) renderer.clone();
                myRendererChooser.addElement(clonedRenderer, clonedRenderer.isEnabled());
                if (elementsToSelect.size() == 0) {
                    elementsToSelect.add(clonedRenderer);
                }
                return true;
            }
        });
        myRendererChooser.selectElements(elementsToSelect);
        updateCurrentRenderer(elementsToSelect);
        myRendererDataConfigurable.reset();
    }

    public void addRenderer(NodeRenderer renderer) {
        myRendererChooser.addElement(renderer, renderer.isEnabled());
    }

    private class AddAction implements AnActionButtonRunnable {
        //public AddAction() {
        //  super(DebuggerBundle.message("button.add"), DebuggerBundle.message("user.renderers.configurable.button.description.add"), ADD_ICON);
        //}

        @Override
        public void run(AnActionButton button) {
            final NodeRenderer renderer = (NodeRenderer) NodeRendererSettings.getInstance().createRenderer(CompoundNodeRenderer.UNIQUE_ID);
            renderer.setEnabled(true);
            addRenderer(renderer);
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    myNameField.requestFocus();
                }
            });
        }
    }

    private class RemoveAction implements AnActionButtonRunnable {
        //public RemoveAction() {
        //  super(DebuggerBundle.message("button.remove"), DebuggerBundle.message("user.renderers.configurable.button.description.remove"), REMOVE_ICON);
        //}


        @Override
        public void run(AnActionButton button) {
            for (NodeRenderer selectedElement : myRendererChooser.getSelectedElements()) {
                myRendererChooser.removeElement(selectedElement);
            }
        }
    }

    private class CopyAction extends AnActionButton {
        public CopyAction() {
            super(DebuggerBundle.message("button.copy"), DebuggerBundle.message("user.renderers.configurable.button.description.copy"), AllIcons.Actions.Copy);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            final NodeRenderer selectedElement = myRendererChooser.getSelectedElement();
            if (selectedElement != null) {
                myRendererChooser.addElement((NodeRenderer) selectedElement.clone(), true);
            }
        }

        @Override
        public void updateButton(AnActionEvent e) {
            super.updateButton(e);
            e.getPresentation().setEnabled(myRendererChooser.getSelectedElement() != null);
        }
    }

    private class MoveAction implements AnActionButtonRunnable {
        private final boolean myMoveUp;

        public MoveAction(boolean up) {
            //super(up? DebuggerBundle.message("button.move.up") : DebuggerBundle.message("button.move.down"),
            //      up? DebuggerBundle.message("user.renderers.configurable.button.description.move.up") : DebuggerBundle.message("user.renderers.configurable.button.description.move.down"),
            //      up? UP_ICON : DOWN_ICON );
            myMoveUp = up;
        }

        @Override
        public void run(AnActionButton button) {
            final int selectedRow = myRendererChooser.getSelectedElementRow();
            if (selectedRow < 0) {
                return;
            }
            int newRow = selectedRow + (myMoveUp ? -1 : 1);
            if (newRow < 0) {
                newRow = myRendererChooser.getElementCount() - 1;
            }
            else if (newRow >= myRendererChooser.getElementCount()) {
                newRow = 0;
            }
            myRendererChooser.moveElement(myRendererChooser.getElementAt(selectedRow), newRow);
        }
    }
}
