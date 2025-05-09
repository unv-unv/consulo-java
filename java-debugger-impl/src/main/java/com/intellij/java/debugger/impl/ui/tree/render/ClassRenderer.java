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
package com.intellij.java.debugger.impl.ui.tree.render;

import com.intellij.java.debugger.DebuggerBundle;
import com.intellij.java.debugger.DebuggerContext;
import com.intellij.java.debugger.engine.DebuggerUtils;
import com.intellij.java.debugger.engine.evaluation.EvaluateException;
import com.intellij.java.debugger.engine.evaluation.EvaluationContext;
import com.intellij.java.debugger.engine.jdi.StackFrameProxy;
import com.intellij.java.debugger.impl.engine.DebuggerManagerThreadImpl;
import com.intellij.java.debugger.impl.ui.impl.watch.FieldDescriptorImpl;
import com.intellij.java.debugger.impl.ui.impl.watch.MessageDescriptor;
import com.intellij.java.debugger.impl.ui.impl.watch.NodeManagerImpl;
import com.intellij.java.debugger.impl.ui.impl.watch.ValueDescriptorImpl;
import com.intellij.java.debugger.impl.ui.tree.*;
import com.intellij.java.debugger.ui.tree.NodeDescriptor;
import com.intellij.java.language.psi.CommonClassNames;
import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiElementFactory;
import consulo.execution.debug.setting.XDebuggerSettingsManager;
import consulo.internal.com.sun.jdi.*;
import consulo.language.psi.PsiElement;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.util.lang.Comparing;
import consulo.util.lang.StringUtil;
import consulo.util.xml.serializer.DefaultJDOMExternalizer;
import consulo.util.xml.serializer.InvalidDataException;
import consulo.util.xml.serializer.WriteExternalException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;

import java.util.*;

/**
 * @author lex
 * @since 2003-09-17
 */
public class ClassRenderer extends NodeRendererImpl
{
	private static final Logger LOG = Logger.getInstance(ClassRenderer.class);

	public static final
	@NonNls
	String UNIQUE_ID = "ClassRenderer";

	public boolean SHOW_SYNTHETICS = true;
	public boolean SHOW_VAL_FIELDS_AS_LOCAL_VARIABLES = true;

	public boolean SHOW_FQ_TYPE_NAMES = false;
	public boolean SHOW_DECLARED_TYPE = false;
	public boolean SHOW_OBJECT_ID = true;

	public boolean SHOW_STRINGS_TYPE = false;

	public ClassRenderer()
	{
		myProperties.setEnabled(true);
	}

	public final String renderTypeName(final String typeName)
	{
		if(SHOW_FQ_TYPE_NAMES)
		{
			return typeName;
		}
		final int dotIndex = typeName.lastIndexOf('.');
		if(dotIndex > 0)
		{
			return typeName.substring(dotIndex + 1);
		}
		return typeName;
	}

	@Override
	public String getUniqueId()
	{
		return UNIQUE_ID;
	}

	@Override
	public boolean isEnabled()
	{
		return myProperties.isEnabled();
	}

	@Override
	public void setEnabled(boolean enabled)
	{
		myProperties.setEnabled(enabled);
	}

	@Override
	public ClassRenderer clone()
	{
		return (ClassRenderer) super.clone();
	}

	@Override
	public String calcLabel(ValueDescriptor descriptor, EvaluationContext evaluationContext, DescriptorLabelListener labelListener) throws EvaluateException
	{
		return calcLabel(descriptor);
	}

	protected static String calcLabel(ValueDescriptor descriptor)
	{
		final ValueDescriptorImpl valueDescriptor = (ValueDescriptorImpl) descriptor;
		final Value value = valueDescriptor.getValue();
		if(value instanceof ObjectReference)
		{
			if(value instanceof StringReference)
			{
				return ((StringReference) value).value();
			}
			else if(value instanceof ClassObjectReference)
			{
				ReferenceType type = ((ClassObjectReference) value).reflectedType();
				return (type != null) ? type.name() : "{...}";
			}
			else
			{
				final ObjectReference objRef = (ObjectReference) value;
				final Type type = objRef.type();
				if(type instanceof ClassType && ((ClassType) type).isEnum())
				{
					final String name = getEnumConstantName(objRef, (ClassType) type);
					if(name != null)
					{
						return name;
					}
					else
					{
						return type.name();
					}
				}
				else
				{
					return "";
				}
			}
		}
		else if(value == null)
		{
			//noinspection HardCodedStringLiteral
			return "null";
		}
		else
		{
			return DebuggerBundle.message("label.undefined");
		}
	}

	@Override
	public void buildChildren(final Value value, final ChildrenBuilder builder, final EvaluationContext evaluationContext)
	{
		DebuggerManagerThreadImpl.assertIsManagerThread();
		final ValueDescriptorImpl parentDescriptor = (ValueDescriptorImpl) builder.getParentDescriptor();
		final NodeManager nodeManager = builder.getNodeManager();
		final NodeDescriptorFactory nodeDescriptorFactory = builder.getDescriptorManager();

		List<DebuggerTreeNode> children = new ArrayList<DebuggerTreeNode>();
		if(value instanceof ObjectReference)
		{
			final ObjectReference objRef = (ObjectReference) value;
			final ReferenceType refType = objRef.referenceType();
			// default ObjectReference processing
			List<Field> fields = refType.allFields();
			if(!fields.isEmpty())
			{
				Set<String> names = new HashSet<String>();
				for(Field field : fields)
				{
					if(shouldDisplay(evaluationContext, objRef, field))
					{
						FieldDescriptor fieldDescriptor = createFieldDescriptor(parentDescriptor, nodeDescriptorFactory, objRef, field, evaluationContext);
						String name = fieldDescriptor.getName();
						if(names.contains(name))
						{
							fieldDescriptor.putUserData(FieldDescriptor.SHOW_DECLARING_TYPE, Boolean.TRUE);
						}
						else
						{
							names.add(name);
						}
						children.add(nodeManager.createNode(fieldDescriptor, evaluationContext));
					}
				}

				if(children.isEmpty())
				{
					children.add(nodeManager.createMessageNode(DebuggerBundle.message("message.node.class.no.fields.to.display")));
				}
				else if(XDebuggerSettingsManager.getInstance().getDataViewSettings().isSortValues())
				{
					Collections.sort(children, NodeManagerImpl.getNodeComparator());
				}
			}
			else
			{
				children.add(nodeManager.createMessageNode(MessageDescriptor.CLASS_HAS_NO_FIELDS.getLabel()));
			}
		}
		builder.setChildren(children);
	}

	@Nonnull
	protected FieldDescriptor createFieldDescriptor(ValueDescriptorImpl parentDescriptor,
			NodeDescriptorFactory nodeDescriptorFactory,
			ObjectReference objRef,
			Field field,
			EvaluationContext evaluationContext)
	{
		return nodeDescriptorFactory.getFieldDescriptor(parentDescriptor, objRef, field);
	}

	protected boolean shouldDisplay(EvaluationContext context, @Nonnull ObjectReference objInstance, @Nonnull Field field)
	{
		final boolean isSynthetic = DebuggerUtils.isSynthetic(field);
		if(!SHOW_SYNTHETICS && isSynthetic)
		{
			return false;
		}
		if(SHOW_VAL_FIELDS_AS_LOCAL_VARIABLES && isSynthetic)
		{
			try
			{
				final StackFrameProxy frameProxy = context.getFrameProxy();
				if(frameProxy != null)
				{
					final Location location = frameProxy.location();
					if(location != null && objInstance.equals(context.getThisObject()) && Comparing.equal(objInstance.referenceType(), location.declaringType()) && StringUtil.startsWith(field.name()
							, FieldDescriptorImpl.OUTER_LOCAL_VAR_FIELD_PREFIX))
					{
						return false;
					}
				}
			}
			catch(EvaluateException ignored)
			{
			}
		}

		// dont show static fields
		return !field.isStatic();
	}

	@Override
	public void readExternal(Element element) throws InvalidDataException
	{
		super.readExternal(element);
		DefaultJDOMExternalizer.readExternal(this, element);
	}

	@Override
	public void writeExternal(Element element) throws WriteExternalException
	{
		super.writeExternal(element);
		DefaultJDOMExternalizer.writeExternal(this, element);
	}

	@Override
	public PsiElement getChildValueExpression(DebuggerTreeNode node, DebuggerContext context) throws EvaluateException
	{
		FieldDescriptor fieldDescriptor = (FieldDescriptor) node.getDescriptor();

		PsiElementFactory elementFactory = JavaPsiFacade.getInstance(node.getProject()).getElementFactory();
		try
		{
			return elementFactory.createExpressionFromText(fieldDescriptor.getField().name(), DebuggerUtils.findClass(fieldDescriptor.getObject().referenceType().name(), context.getProject(),
					context.getDebugProcess().getSearchScope()));
		}
		catch(IncorrectOperationException e)
		{
			throw new EvaluateException(DebuggerBundle.message("error.invalid.field.name", fieldDescriptor.getField().name()), null);
		}
	}

	private static boolean valueExpandable(Value value)
	{
		try
		{
			if(value instanceof ArrayReference)
			{
				return ((ArrayReference) value).length() > 0;
			}
			else if(value instanceof ObjectReference)
			{
				return true; // if object has no fields, it contains a child-message about that
				//return ((ObjectReference)value).referenceType().allFields().size() > 0;
			}
		}
		catch(ObjectCollectedException e)
		{
			return true;
		}

		return false;
	}

	@Override
	public boolean isExpandable(Value value, EvaluationContext evaluationContext, NodeDescriptor parentDescriptor)
	{
		DebuggerManagerThreadImpl.assertIsManagerThread();
		return valueExpandable(value);
	}

	@Override
	public boolean isApplicable(Type type)
	{
		return type instanceof ReferenceType && !(type instanceof ArrayType);
	}

	@Override
	public
	@NonNls
	String getName()
	{
		return "Object";
	}

	@Override
	public void setName(String text)
	{
		LOG.assertTrue(false);
	}

	@Nullable
	public static String getEnumConstantName(@Nonnull ObjectReference objRef, ClassType classType)
	{
		do
		{
			if(!classType.isPrepared())
			{
				return null;
			}
			classType = classType.superclass();
			if(classType == null)
			{
				return null;
			}
		}
		while(!(CommonClassNames.JAVA_LANG_ENUM.equals(classType.name())));
		//noinspection HardCodedStringLiteral
		final Field field = classType.fieldByName("name");
		if(field == null)
		{
			return null;
		}
		final Value value = objRef.getValue(field);
		if(!(value instanceof StringReference))
		{
			return null;
		}
		return ((StringReference) value).value();
	}
}
