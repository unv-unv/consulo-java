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
package com.intellij.debugger.engine;

import java.util.List;

import javax.swing.Icon;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.debugger.DebuggerInvocationUtil;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.actions.JavaValueModifier;
import com.intellij.debugger.actions.JumpToObjectAction;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.impl.DebuggerContextImpl;
import com.intellij.debugger.impl.DebuggerUtilsEx;
import com.intellij.debugger.ui.impl.DebuggerTreeRenderer;
import com.intellij.debugger.ui.impl.watch.FieldDescriptorImpl;
import com.intellij.debugger.ui.impl.watch.LocalVariableDescriptorImpl;
import com.intellij.debugger.ui.impl.watch.MessageDescriptor;
import com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider;
import com.intellij.debugger.ui.impl.watch.NodeManagerImpl;
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl;
import com.intellij.debugger.ui.tree.DebuggerTreeNode;
import com.intellij.debugger.ui.tree.NodeDescriptor;
import com.intellij.debugger.ui.tree.NodeDescriptorFactory;
import com.intellij.debugger.ui.tree.NodeManager;
import com.intellij.debugger.ui.tree.ValueDescriptor;
import com.intellij.debugger.ui.tree.render.ArrayRenderer;
import com.intellij.debugger.ui.tree.render.ChildrenBuilder;
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener;
import com.intellij.debugger.ui.tree.render.NodeRenderer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiExpression;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XNavigatable;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueModifier;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation;
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;

/**
 * @author egor
 */
public class JavaValue extends XNamedValue implements NodeDescriptorProvider
{
	private static final Logger LOG = Logger.getInstance(JavaValue.class);

	private final JavaValue myParent;
	private final ValueDescriptorImpl myValueDescriptor;
	private final EvaluationContextImpl myEvaluationContext;
	private final NodeManagerImpl myNodeManager;

	private JavaValue(
			JavaValue parent,
			@NotNull ValueDescriptorImpl valueDescriptor,
			EvaluationContextImpl evaluationContext,
			NodeManagerImpl nodeManager)
	{
		super(valueDescriptor.getName());
		myParent = parent;
		myValueDescriptor = valueDescriptor;
		myEvaluationContext = evaluationContext;
		myNodeManager = nodeManager;
	}

	private static JavaValue create(
			JavaValue parent,
			@NotNull ValueDescriptorImpl valueDescriptor,
			EvaluationContextImpl evaluationContext,
			NodeManagerImpl nodeManager)
	{
		DebuggerManagerThreadImpl.assertIsManagerThread();
		valueDescriptor.setContext(evaluationContext);
		valueDescriptor.updateRepresentation(evaluationContext, DescriptorLabelListener.DUMMY_LISTENER);
		return new JavaValue(parent, valueDescriptor, evaluationContext, nodeManager);
	}

	static JavaValue create(@NotNull ValueDescriptorImpl valueDescriptor, EvaluationContextImpl evaluationContext, NodeManagerImpl nodeManager)
	{
		return create(null, valueDescriptor, evaluationContext, nodeManager);
	}

	public JavaValue getParent()
	{
		return myParent;
	}

	@Override
	public ValueDescriptorImpl getDescriptor()
	{
		return myValueDescriptor;
	}

	public EvaluationContextImpl getEvaluationContext()
	{
		return myEvaluationContext;
	}

	@Override
	public void computePresentation(@NotNull final XValueNode node, @NotNull XValuePlace place)
	{
		if(myEvaluationContext.getSuspendContext().isResumed())
		{
			return;
		}
		myEvaluationContext.getDebugProcess().getManagerThread().schedule(new DebuggerContextCommandImpl(getDebuggerContext())
		{
			@Override
			public void threadAction()
			{
				Icon nodeIcon = DebuggerTreeRenderer.getValueIcon(myValueDescriptor);
				final String[] strings = splitValue(myValueDescriptor.getValueLabel());
				String value = StringUtil.notNullize(strings[1]);
				XValuePresentation presentation = new XRegularValuePresentation(value, strings[0]);
				if(myValueDescriptor.isString())
				{
					presentation = new TypedStringValuePresentation(StringUtil.unquoteString(value), strings[0]);
				}
				if(value.length() > XValueNode.MAX_VALUE_LENGTH)
				{
					node.setFullValueEvaluator(new XFullValueEvaluator()
					{
						@Override
						public void startEvaluation(@NotNull final XFullValueEvaluationCallback callback)
						{
							myEvaluationContext.getDebugProcess().getManagerThread().schedule(new DebuggerContextCommandImpl(getDebuggerContext())
							{
								@Override
								public void threadAction()
								{
									final String valueAsString = DebuggerUtilsEx.getValueOrErrorAsString(myEvaluationContext,
											myValueDescriptor.getValue());
									DebuggerInvocationUtil.invokeLater(getProject(), new Runnable()
									{
										@Override
										public void run()
										{
											callback.evaluated(valueAsString);
										}
									});
								}
							});
						}
					});
				}
				node.setPresentation(nodeIcon, presentation, myValueDescriptor.isExpandable());
			}
		});
	}

	String getValueString()
	{
		return splitValue(myValueDescriptor.getValueLabel())[1];
	}

	private static class TypedStringValuePresentation extends XStringValuePresentation
	{
		private final String myType;

		public TypedStringValuePresentation(@NotNull String value, @Nullable String type)
		{
			super(value);
			myType = type;
		}

		@Nullable
		@Override
		public String getType()
		{
			return myType;
		}
	}

	private static String[] splitValue(String value)
	{
		if(StringUtil.startsWithChar(value, '{'))
		{
			int end = value.indexOf('}');
			if(end > 0)
			{
				return new String[]{
						value.substring(1, end),
						value.substring(end + 1)
				};
			}
		}
		return new String[]{
				null,
				value
		};
	}

	private int currentStart = 0;

	@Override
	public void computeChildren(@NotNull final XCompositeNode node)
	{
		if(myEvaluationContext.getSuspendContext().isResumed())
		{
			return;
		}
		myEvaluationContext.getDebugProcess().getManagerThread().schedule(new SuspendContextCommandImpl(myEvaluationContext.getSuspendContext())
		{
			@Override
			public void contextAction() throws Exception
			{
				final XValueChildrenList children = new XValueChildrenList();
				final NodeRenderer renderer = myValueDescriptor.getRenderer(myEvaluationContext.getDebugProcess());
				final Ref<Integer> remainingNum = new Ref<Integer>(0);
				renderer.buildChildren(myValueDescriptor.getValue(), new ChildrenBuilder()
				{
					@Override
					public NodeDescriptorFactory getDescriptorManager()
					{
						return myNodeManager;
					}

					@Override
					public NodeManager getNodeManager()
					{
						return myNodeManager;
					}

					@Override
					public ValueDescriptor getParentDescriptor()
					{
						return myValueDescriptor;
					}

					@Override
					public void setRemaining(int remaining)
					{
						remainingNum.set(remaining);
					}

					@Override
					public void initChildrenArrayRenderer(ArrayRenderer renderer)
					{
						renderer.START_INDEX = currentStart;
						renderer.END_INDEX = currentStart + XCompositeNode.MAX_CHILDREN_TO_SHOW - 1;
						currentStart += XCompositeNode.MAX_CHILDREN_TO_SHOW;
					}

					@Override
					public void setChildren(List<DebuggerTreeNode> nodes)
					{
						for(DebuggerTreeNode node : nodes)
						{
							final NodeDescriptor descriptor = node.getDescriptor();
							if(descriptor instanceof ValueDescriptorImpl)
							{
								children.add(create(JavaValue.this, (ValueDescriptorImpl) descriptor, myEvaluationContext, myNodeManager));
							}
							else if(descriptor instanceof MessageDescriptor)
							{
								children.add("", new XValue()
								{
									@Override
									public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place)
									{
										node.setPresentation(null, new XValuePresentation()
										{
											@NotNull
											@Override
											public String getSeparator()
											{
												return "";
											}

											@Override
											public void renderValue(@NotNull XValueTextRenderer renderer)
											{
												renderer.renderValue(descriptor.getLabel());
											}
										}, false);
									}
								});
							}
						}
					}
				}, myEvaluationContext);
				node.addChildren(children, true);
				if(remainingNum.get() > 0)
				{
					node.tooManyChildren(remainingNum.get());
				}
			}
		});
	}

	@Override
	public void computeSourcePosition(@NotNull XNavigatable navigatable)
	{
		if(myValueDescriptor instanceof FieldDescriptorImpl)
		{
			SourcePosition position = ((FieldDescriptorImpl) myValueDescriptor).getSourcePosition(getProject(), getDebuggerContext());
			if(position != null)
			{
				navigatable.setSourcePosition(DebuggerUtilsEx.toXSourcePosition(position));
			}
		}
		if(myValueDescriptor instanceof LocalVariableDescriptorImpl)
		{
			SourcePosition position = ((LocalVariableDescriptorImpl) myValueDescriptor).getSourcePosition(getProject(), getDebuggerContext());
			if(position != null)
			{
				navigatable.setSourcePosition(DebuggerUtilsEx.toXSourcePosition(position));
			}
		}
	}

	private DebuggerContextImpl getDebuggerContext()
	{
		return myEvaluationContext.getDebugProcess().getDebuggerContext();
	}

	public Project getProject()
	{
		return myEvaluationContext.getProject();
	}

	@Override
	public boolean canNavigateToTypeSource()
	{
		return true;
	}

	@Override
	public void computeTypeSourcePosition(@NotNull final XNavigatable navigatable)
	{
		if(myEvaluationContext.getSuspendContext().isResumed())
		{
			return;
		}
		DebugProcessImpl debugProcess = myEvaluationContext.getDebugProcess();
		debugProcess.getManagerThread().schedule(new JumpToObjectAction.NavigateCommand(getDebuggerContext(), myValueDescriptor, debugProcess, null)
		{
			@Override
			protected void doAction(@Nullable final SourcePosition sourcePosition)
			{
				if(sourcePosition != null)
				{
					ApplicationManager.getApplication().runReadAction(new Runnable()
					{
						@Override
						public void run()
						{
							navigatable.setSourcePosition(DebuggerUtilsEx.toXSourcePosition(sourcePosition));
						}
					});
				}
			}
		});
	}

	@Nullable
	@Override
	public XValueModifier getModifier()
	{
		return myValueDescriptor.canSetValue() ? new JavaValueModifier(this) : null;
	}


	private volatile String evaluationExpression = null;

	@Nullable
	@Override
	public String getEvaluationExpression()
	{
		if(evaluationExpression == null)
		{
			// TODO: change API to allow to calculate it asynchronously
			DebugProcessImpl debugProcess = myEvaluationContext.getDebugProcess();
			debugProcess.getManagerThread().invokeAndWait(new DebuggerCommandImpl()
			{
				@Override
				protected void action() throws Exception
				{
					evaluationExpression = ApplicationManager.getApplication().runReadAction(new Computable<String>()
					{
						@Override
						public String compute()
						{
							try
							{
								PsiExpression psiExpression = getDescriptor().getTreeEvaluation(JavaValue.this, getDebuggerContext());
								if(psiExpression != null)
								{
									return new TextWithImportsImpl(psiExpression).getText();
								}
							}
							catch(EvaluateException e)
							{
								LOG.info(e);
							}
							return null;
						}
					});
				}
			});
		}
		return evaluationExpression;
	}
}