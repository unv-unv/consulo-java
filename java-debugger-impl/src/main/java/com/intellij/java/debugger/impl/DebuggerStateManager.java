/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.java.debugger.impl;

import consulo.annotation.DeprecationInfo;
import consulo.localize.LocalizeValue;
import consulo.proxy.EventDispatcher;
import jakarta.annotation.Nonnull;

public abstract class DebuggerStateManager {
    private final EventDispatcher<DebuggerContextListener> myEventDispatcher = EventDispatcher.create(DebuggerContextListener.class);

    @Nonnull
    public abstract DebuggerContextImpl getContext();

    public void setState(
        @Nonnull DebuggerContextImpl context,
        DebuggerSession.State state,
        DebuggerSession.Event event,
        @Nonnull LocalizeValue description
    ) {
        setState(context, state, event, description.get());
    }

    public void setState(
        @Nonnull DebuggerContextImpl context,
        DebuggerSession.State state,
        DebuggerSession.Event event
    ) {
        setState(context, state, event, (String)null);
    }

    @Deprecated
    @DeprecationInfo("Use variant with LocalizeValue")
    public abstract void setState(
        @Nonnull DebuggerContextImpl context,
        DebuggerSession.State state,
        DebuggerSession.Event event,
        String description
    );

    //we allow add listeners inside DebuggerContextListener.changeEvent
    public void addListener(DebuggerContextListener listener) {
        myEventDispatcher.addListener(listener);
    }

    //we allow remove listeners inside DebuggerContextListener.changeEvent
    public void removeListener(DebuggerContextListener listener) {
        myEventDispatcher.removeListener(listener);
    }

    protected void fireStateChanged(@Nonnull DebuggerContextImpl newContext, DebuggerSession.Event event) {
        myEventDispatcher.getMulticaster().changeEvent(newContext, event);
    }

    void dispose() {
        myEventDispatcher.getListeners().clear();
    }
}
