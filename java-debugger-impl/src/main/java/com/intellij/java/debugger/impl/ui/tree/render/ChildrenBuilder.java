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
package com.intellij.java.debugger.impl.ui.tree.render;

import java.util.List;

import com.intellij.java.debugger.impl.ui.tree.DebuggerTreeNode;
import com.intellij.java.debugger.impl.ui.tree.NodeDescriptorFactory;
import com.intellij.java.debugger.impl.ui.tree.NodeManager;
import com.intellij.java.debugger.impl.ui.tree.ValueDescriptor;
import consulo.execution.debug.frame.XCompositeNode;

public interface ChildrenBuilder extends XCompositeNode
{
	NodeDescriptorFactory getDescriptorManager();

	NodeManager getNodeManager();

	ValueDescriptor getParentDescriptor();

	void setChildren(List<DebuggerTreeNode> children);

	default void addChildren(List<DebuggerTreeNode> children, boolean last)
	{
		setChildren(children);
	}

	/**
	 * @deprecated use {@link #tooManyChildren}
	 */
	@Deprecated
	default void setRemaining(int remaining)
	{
		tooManyChildren(remaining);
	}

	void initChildrenArrayRenderer(ArrayRenderer renderer, int arrayLength);
}
