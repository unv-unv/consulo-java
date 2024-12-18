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

/*
 * Class Breakpoint
 * @author Jeka
 */
package com.intellij.java.debugger.impl.ui.breakpoints;

import com.intellij.java.debugger.DebuggerBundle;
import com.intellij.java.debugger.SourcePosition;
import com.intellij.java.debugger.engine.DebugProcess;
import com.intellij.java.debugger.engine.DebugProcessListener;
import com.intellij.java.debugger.engine.DebuggerUtils;
import com.intellij.java.debugger.engine.SuspendContext;
import com.intellij.java.debugger.engine.evaluation.EvaluateException;
import com.intellij.java.debugger.engine.evaluation.EvaluateExceptionUtil;
import com.intellij.java.debugger.engine.evaluation.TextWithImports;
import com.intellij.java.debugger.engine.evaluation.expression.ExpressionEvaluator;
import com.intellij.java.debugger.impl.*;
import com.intellij.java.debugger.impl.breakpoints.properties.JavaBreakpointProperties;
import com.intellij.java.debugger.impl.engine.ContextUtil;
import com.intellij.java.debugger.impl.engine.DebugProcessImpl;
import com.intellij.java.debugger.impl.engine.JavaDebugProcess;
import com.intellij.java.debugger.impl.engine.SuspendContextImpl;
import com.intellij.java.debugger.impl.engine.evaluation.EvaluationContextImpl;
import com.intellij.java.debugger.impl.engine.evaluation.TextWithImportsImpl;
import com.intellij.java.debugger.impl.engine.evaluation.expression.EvaluatorBuilderImpl;
import com.intellij.java.debugger.impl.engine.evaluation.expression.UnsupportedExpressionException;
import com.intellij.java.debugger.impl.engine.events.SuspendContextCommandImpl;
import com.intellij.java.debugger.impl.jdi.StackFrameProxyImpl;
import com.intellij.java.debugger.impl.jdi.ThreadReferenceProxyImpl;
import com.intellij.java.debugger.impl.settings.DebuggerSettings;
import com.intellij.java.debugger.impl.ui.impl.watch.CompilingEvaluatorImpl;
import com.intellij.java.debugger.requests.ClassPrepareRequestor;
import com.intellij.java.debugger.requests.Requestor;
import com.intellij.java.debugger.ui.classFilter.ClassFilter;
import com.intellij.java.language.psi.PsiClass;
import consulo.application.ApplicationManager;
import consulo.application.ReadAction;
import consulo.execution.debug.XDebuggerHistoryManager;
import consulo.execution.debug.XDebuggerUtil;
import consulo.execution.debug.breakpoint.SuspendPolicy;
import consulo.execution.debug.breakpoint.XBreakpoint;
import consulo.execution.debug.breakpoint.XExpression;
import consulo.execution.debug.breakpoint.XLineBreakpoint;
import consulo.internal.com.sun.jdi.*;
import consulo.internal.com.sun.jdi.event.LocatableEvent;
import consulo.internal.com.sun.jdi.request.EventRequest;
import consulo.language.psi.PsiCodeFragment;
import consulo.language.psi.PsiElement;
import consulo.project.Project;
import consulo.project.ui.util.AppUIUtil;
import consulo.ui.image.Image;
import consulo.util.dataholder.Key;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.ThreeState;
import consulo.util.xml.serializer.InvalidDataException;
import consulo.util.xml.serializer.JDOMExternalizerUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

public abstract class Breakpoint<P extends JavaBreakpointProperties> implements FilteredRequestor, ClassPrepareRequestor
{
	public static final Key<Breakpoint> DATA_KEY = Key.create("JavaBreakpoint");
	private static final Key<Long> HIT_COUNTER = Key.create("HIT_COUNTER");

	final XBreakpoint<P> myXBreakpoint;
	protected final Project myProject;

	@NonNls
	private static final String LOG_MESSAGE_OPTION_NAME = "LOG_MESSAGE";
	protected boolean myCachedVerifiedState = false;

	protected Breakpoint(@Nonnull Project project, XBreakpoint<P> xBreakpoint)
	{
		myProject = project;
		myXBreakpoint = xBreakpoint;
	}

	@Nonnull
	public Project getProject()
	{
		return myProject;
	}

	@Nonnull
	protected P getProperties()
	{
		return myXBreakpoint.getProperties();
	}

	public XBreakpoint<P> getXBreakpoint()
	{
		return myXBreakpoint;
	}

	@Nullable
	public abstract PsiClass getPsiClass();

	/**
	 * Request for creating all needed JPDA requests in the specified VM
	 *
	 * @param debugProcess the requesting process
	 */
	public abstract void createRequest(DebugProcessImpl debugProcess);

	static boolean shouldCreateRequest(Requestor requestor, XBreakpoint xBreakpoint, DebugProcessImpl debugProcess, boolean forPreparedClass)
	{
		return ReadAction.compute(() ->
		{
			JavaDebugProcess process = debugProcess.getXdebugProcess();
			return process != null && debugProcess.isAttached() && (xBreakpoint == null || process.getSession().isBreakpointActive(xBreakpoint)) && (forPreparedClass ||
					debugProcess.getRequestsManager().findRequests(requestor).isEmpty());
		});
	}

	protected final boolean shouldCreateRequest(DebugProcessImpl debugProcess, boolean forPreparedClass)
	{
		return shouldCreateRequest(this, getXBreakpoint(), debugProcess, forPreparedClass);
	}

	protected final boolean shouldCreateRequest(DebugProcessImpl debugProcess)
	{
		return shouldCreateRequest(debugProcess, false);
	}

	/**
	 * Request for creating all needed JPDA requests in the specified VM
	 *
	 * @param debuggerProcess the requesting process
	 */
	@Override
	public abstract void processClassPrepare(DebugProcess debuggerProcess, final ReferenceType referenceType);

	public abstract String getDisplayName();

	public String getShortName()
	{
		return getDisplayName();
	}

	@Nullable
	public String getClassName()
	{
		return null;
	}

	public void markVerified(boolean isVerified)
	{
		myCachedVerifiedState = isVerified;
	}

	public boolean isRemoveAfterHit()
	{
		return myXBreakpoint instanceof XLineBreakpoint && ((XLineBreakpoint) myXBreakpoint).isTemporary();
	}

	public void setRemoveAfterHit(boolean value)
	{
		if(myXBreakpoint instanceof XLineBreakpoint)
		{
			((XLineBreakpoint) myXBreakpoint).setTemporary(value);
		}
	}

	@Nullable
	public String getShortClassName()
	{
		final String className = getClassName();
		if(className == null)
		{
			return null;
		}

		final int dotIndex = className.lastIndexOf('.');
		return dotIndex >= 0 && dotIndex + 1 < className.length() ? className.substring(dotIndex + 1) : className;
	}

	@Nullable
	public String getPackageName()
	{
		return null;
	}

	public abstract Image getIcon();

	public abstract void reload();

	/**
	 * returns UI representation
	 */
	public abstract String getEventMessage(LocatableEvent event);

	public abstract boolean isValid();

	public abstract Key<? extends Breakpoint> getCategory();

	/**
	 * Associates breakpoint with class.
	 * Create requests for loaded class and registers callback for loading classes
	 *
	 * @param debugProcess the requesting process
	 */
	protected void createOrWaitPrepare(DebugProcessImpl debugProcess, String classToBeLoaded)
	{
		debugProcess.getRequestsManager().callbackOnPrepareClasses(this, classToBeLoaded);

		debugProcess.getVirtualMachineProxy().classesByName(classToBeLoaded).stream().filter(ReferenceType::isPrepared).forEach(aList -> processClassPrepare(debugProcess, aList));
	}

	protected void createOrWaitPrepare(final DebugProcessImpl debugProcess, @Nonnull final SourcePosition classPosition)
	{
		debugProcess.getRequestsManager().callbackOnPrepareClasses(this, classPosition);

		debugProcess.getPositionManager().getAllClasses(classPosition).stream().filter(ReferenceType::isPrepared).forEach(refType -> processClassPrepare(debugProcess, refType));
	}

	protected ObjectReference getThisObject(SuspendContextImpl context, LocatableEvent event) throws EvaluateException
	{
		ThreadReferenceProxyImpl thread = context.getThread();
		if(thread != null)
		{
			StackFrameProxyImpl stackFrameProxy = thread.frame(0);
			if(stackFrameProxy != null)
			{
				return stackFrameProxy.thisObject();
			}
		}
		return null;
	}

	@Override
	public boolean processLocatableEvent(SuspendContextCommandImpl action, LocatableEvent event) throws EventProcessingException
	{
		SuspendContextImpl context = action.getSuspendContext();
		if(!isValid())
		{
			context.getDebugProcess().getRequestsManager().deleteRequest(this);
			return false;
		}

		String title = DebuggerBundle.message("title.error.evaluating.breakpoint.condition");

		try
		{
			StackFrameProxyImpl frameProxy = context.getThread().frame(0);
			if(frameProxy == null)
			{
				// might be if the thread has been collected
				return false;
			}

			EvaluationContextImpl evaluationContext = new EvaluationContextImpl(context, frameProxy, getThisObject(context, event));

			if(!evaluateCondition(evaluationContext, event))
			{
				return false;
			}

			title = DebuggerBundle.message("title.error.evaluating.breakpoint.action");
			runAction(evaluationContext, event);
		}
		catch(final EvaluateException ex)
		{
			if(ApplicationManager.getApplication().isUnitTestMode())
			{
				System.out.println(ex.getMessage());
				return false;
			}

			throw new EventProcessingException(title, ex.getMessage(), ex);
		}

		return true;
	}

	private void runAction(EvaluationContextImpl context, LocatableEvent event)
	{
		DebugProcessImpl debugProcess = context.getDebugProcess();
		if(isLogEnabled() || isLogExpressionEnabled())
		{
			StringBuilder buf = new StringBuilder();
			if(myXBreakpoint.isLogMessage())
			{
				buf.append(getEventMessage(event)).append("\n");
			}
			if(isLogExpressionEnabled())
			{
				if(!debugProcess.isAttached())
				{
					return;
				}

				TextWithImports logMessage = getLogMessage();
				try
				{
					SourcePosition position = ContextUtil.getSourcePosition(context);
					PsiElement element = ContextUtil.getContextElement(context, position);
					ExpressionEvaluator evaluator = DebuggerInvocationUtil.commitAndRunReadAction(myProject, () -> EvaluatorCache.cacheOrGet("LogMessageEvaluator", event.request(), element,
							logMessage, () -> createExpressionEvaluator(myProject, element, position, logMessage, this::createLogMessageCodeFragment)));
					Value eval = evaluator.evaluate(context);
					buf.append(eval instanceof VoidValue ? "void" : DebuggerUtils.getValueAsString(context, eval));
				}
				catch(EvaluateException e)
				{
					buf.append(DebuggerBundle.message("error.unable.to.evaluate.expression")).append(" \"").append(logMessage).append("\"").append(" : ").append(e.getMessage());
				}
				buf.append("\n");
			}
			if(buf.length() > 0)
			{
				debugProcess.printToConsole(buf.toString());
			}
		}
		if(isRemoveAfterHit())
		{
			handleTemporaryBreakpointHit(debugProcess);
		}
	}

	/**
	 * @return true if the ID was added or false otherwise
	 */
	private boolean hasObjectID(long id)
	{
		return Arrays.stream(getInstanceFilters()).anyMatch(instanceFilter -> instanceFilter.getId() == id);
	}

	public boolean evaluateCondition(final EvaluationContextImpl context, LocatableEvent event) throws EvaluateException
	{
		DebugProcessImpl debugProcess = context.getDebugProcess();
		if(isCountFilterEnabled() && !isConditionEnabled())
		{
			debugProcess.getVirtualMachineProxy().suspend();
			debugProcess.getRequestsManager().deleteRequest(this);
			createRequest(debugProcess);
			debugProcess.getVirtualMachineProxy().resume();
		}
		if(isInstanceFiltersEnabled())
		{
			Value value = context.getThisObject();
			if(value != null)
			{  // non-static
				ObjectReference reference = (ObjectReference) value;
				if(!hasObjectID(reference.uniqueID()))
				{
					return false;
				}
			}
		}

		if(isClassFiltersEnabled() && !typeMatchesClassFilters(calculateEventClass(context, event), getClassFilters(), getClassExclusionFilters()))
		{
			return false;
		}

		if(isConditionEnabled())
		{
			TextWithImports condition = getCondition();
			if(condition.isEmpty())
			{
				return true;
			}

			StackFrameProxyImpl frame = context.getFrameProxy();
			if(frame != null)
			{
				Location location = frame.location();
				if(location != null)
				{
					ThreeState result = debugProcess.getPositionManager().evaluateCondition(context, frame, location, condition.getText());
					if(result != ThreeState.UNSURE)
					{
						return result == ThreeState.YES;
					}
				}
			}

			try
			{
				SourcePosition contextSourcePosition = ContextUtil.getSourcePosition(context);
				ExpressionEvaluator evaluator = DebuggerInvocationUtil.commitAndRunReadAction(myProject, () ->
				{
					// IMPORTANT: calculate context psi element basing on the location where the exception
					// has been hit, not on the location where it was set. (For line breakpoints these locations are the same, however,
					// for method, exception and field breakpoints these locations differ)
					PsiElement contextElement = ContextUtil.getContextElement(contextSourcePosition);
					PsiElement contextPsiElement = contextElement != null ? contextElement : getEvaluationElement(); // as a last resort
					return EvaluatorCache.cacheOrGet("ConditionEvaluator", event.request(), contextPsiElement, condition, () -> createExpressionEvaluator(myProject, contextPsiElement,
							contextSourcePosition, condition, this::createConditionCodeFragment));
				});
				if(!DebuggerUtilsEx.evaluateBoolean(evaluator, context))
				{
					return false;
				}
			}
			catch(EvaluateException ex)
			{
				if(ex.getCause() instanceof VMDisconnectedException)
				{
					return false;
				}
				throw EvaluateExceptionUtil.createEvaluateException(DebuggerBundle.message("error.failed.evaluating.breakpoint.condition", condition, ex.getMessage()));
			}
		}
		if(isCountFilterEnabled() && isConditionEnabled())
		{
			Long hitCount = ObjectUtil.notNull((Long) event.request().getProperty(HIT_COUNTER), 0L) + 1;
			event.request().putProperty(HIT_COUNTER, hitCount);
			return hitCount % getCountFilter() == 0;
		}
		return true;
	}

	private static class EvaluatorCache
	{
		private final PsiElement myContext;
		private final TextWithImports myTextWithImports;
		private final ExpressionEvaluator myEvaluator;

		private EvaluatorCache(PsiElement context, TextWithImports textWithImports, ExpressionEvaluator evaluator)
		{
			myContext = context;
			myTextWithImports = textWithImports;
			myEvaluator = evaluator;
		}

		@Nullable
		static ExpressionEvaluator cacheOrGet(String propertyName,
				EventRequest request,
				PsiElement context,
				TextWithImports text,
				EvaluatingComputable<ExpressionEvaluator> supplier) throws EvaluateException
		{
			EvaluatorCache cache = (EvaluatorCache) request.getProperty(propertyName);
			if(cache != null && Objects.equals(cache.myContext, context) && Objects.equals(cache.myTextWithImports, text))
			{
				return cache.myEvaluator;
			}
			ExpressionEvaluator evaluator = supplier.compute();
			request.putProperty(propertyName, new EvaluatorCache(context, text, evaluator));
			return evaluator;
		}
	}

	private static ExpressionEvaluator createExpressionEvaluator(Project project,
			PsiElement contextPsiElement,
			SourcePosition contextSourcePosition,
			TextWithImports text,
			Function<PsiElement, PsiCodeFragment> fragmentFactory) throws EvaluateException
	{
		try
		{
			return EvaluatorBuilderImpl.build(text, contextPsiElement, contextSourcePosition, project);
		}
		catch(UnsupportedExpressionException ex)
		{
			ExpressionEvaluator eval = CompilingEvaluatorImpl.create(project, contextPsiElement, fragmentFactory);
			if(eval != null)
			{
				return eval;
			}
			throw ex;
		}
	}

	private PsiCodeFragment createConditionCodeFragment(PsiElement context)
	{
		return createCodeFragment(myProject, getCondition(), context);
	}

	private PsiCodeFragment createLogMessageCodeFragment(PsiElement context)
	{
		return createCodeFragment(myProject, getLogMessage(), context);
	}

	private static PsiCodeFragment createCodeFragment(Project project, TextWithImports text, PsiElement context)
	{
		return DebuggerUtilsEx.findAppropriateCodeFragmentFactory(text, context).createCodeFragment(text, context, project);
	}

	protected String calculateEventClass(EvaluationContextImpl context, LocatableEvent event) throws EvaluateException
	{
		return event.location().declaringType().name();
	}

	protected static boolean typeMatchesClassFilters(@Nullable String typeName, ClassFilter[] includeFilters, ClassFilter[] exludeFilters)
	{
		if(typeName == null)
		{
			return true;
		}
		boolean matches = false, hasEnabled = false;
		for(ClassFilter classFilter : includeFilters)
		{
			if(classFilter.isEnabled())
			{
				hasEnabled = true;
				if(classFilter.matches(typeName))
				{
					matches = true;
					break;
				}
			}
		}
		if(hasEnabled && !matches)
		{
			return false;
		}
		return Arrays.stream(exludeFilters).noneMatch(classFilter -> classFilter.isEnabled() && classFilter.matches(typeName));
	}

	private void handleTemporaryBreakpointHit(final DebugProcessImpl debugProcess)
	{
		// need to delete the request immediately, see IDEA-133978
		debugProcess.getRequestsManager().deleteRequest(this);

		debugProcess.addDebugProcessListener(new DebugProcessListener()
		{
			@Override
			public void resumed(SuspendContext suspendContext)
			{
				removeBreakpoint();
			}

			@Override
			public void processDetached(DebugProcess process, boolean closedByUser)
			{
				removeBreakpoint();
			}

			private void removeBreakpoint()
			{
				AppUIUtil.invokeOnEdt(() -> DebuggerManagerEx.getInstanceEx(myProject).getBreakpointManager().removeBreakpoint(Breakpoint.this));
				debugProcess.removeDebugProcessListener(this);
			}
		});
	}

	public void updateUI()
	{
	}

	public void readExternal(Element parentNode) throws InvalidDataException
	{
		FilteredRequestorImpl requestor = new FilteredRequestorImpl(myProject);
		requestor.readTo(parentNode, this);
		try
		{
			setEnabled(Boolean.valueOf(JDOMExternalizerUtil.readField(parentNode, "ENABLED")));
		}
		catch(Exception ignored)
		{
		}
		try
		{
			setLogEnabled(Boolean.valueOf(JDOMExternalizerUtil.readField(parentNode, "LOG_ENABLED")));
		}
		catch(Exception ignored)
		{
		}
		try
		{
			String logMessage = JDOMExternalizerUtil.readField(parentNode, LOG_MESSAGE_OPTION_NAME);
			if(logMessage != null && !logMessage.isEmpty())
			{
				XExpression expression = XExpression.fromText(logMessage);
				XDebuggerHistoryManager.getInstance(myProject).addRecentExpression(XDebuggerHistoryManager.BREAKPOINT_CONDITION_HISTORY_ID, expression);
				myXBreakpoint.setLogExpressionObject(expression);
				myXBreakpoint.setLogExpressionEnabled(Boolean.valueOf(JDOMExternalizerUtil.readField(parentNode, "LOG_EXPRESSION_ENABLED")));
			}
		}
		catch(Exception ignored)
		{
		}
		try
		{
			setRemoveAfterHit(Boolean.valueOf(JDOMExternalizerUtil.readField(parentNode, "REMOVE_AFTER_HIT")));
		}
		catch(Exception ignored)
		{
		}
	}

	@Nullable
	public abstract PsiElement getEvaluationElement();

	protected TextWithImports getLogMessage()
	{
		return TextWithImportsImpl.fromXExpression(myXBreakpoint.getLogExpressionObject());
	}

	protected TextWithImports getCondition()
	{
		return TextWithImportsImpl.fromXExpression(myXBreakpoint.getConditionExpression());
	}

	public boolean isEnabled()
	{
		return myXBreakpoint.isEnabled();
	}

	public void setEnabled(boolean enabled)
	{
		myXBreakpoint.setEnabled(enabled);
	}

	protected boolean isLogEnabled()
	{
		return myXBreakpoint.isLogMessage();
	}

	public void setLogEnabled(boolean logEnabled)
	{
		myXBreakpoint.setLogMessage(logEnabled);
	}

	protected boolean isLogExpressionEnabled()
	{
		if(XDebuggerUtil.getInstance().isEmptyExpression(myXBreakpoint.getLogExpressionObject()))
		{
			return false;
		}
		return !getLogMessage().isEmpty();
	}

	@Override
	public boolean isCountFilterEnabled()
	{
		return getProperties().isCOUNT_FILTER_ENABLED() && getCountFilter() > 0;
	}

	public void setCountFilterEnabled(boolean enabled)
	{
		if(getProperties().setCOUNT_FILTER_ENABLED(enabled))
		{
			fireBreakpointChanged();
		}
	}

	@Override
	public int getCountFilter()
	{
		return getProperties().getCOUNT_FILTER();
	}

	public void setCountFilter(int filter)
	{
		if(getProperties().setCOUNT_FILTER(filter))
		{
			fireBreakpointChanged();
		}
	}

	@Override
	public boolean isClassFiltersEnabled()
	{
		return getProperties().isCLASS_FILTERS_ENABLED();
	}

	public void setClassFiltersEnabled(boolean enabled)
	{
		if(getProperties().setCLASS_FILTERS_ENABLED(enabled))
		{
			fireBreakpointChanged();
		}
	}

	@Override
	public ClassFilter[] getClassFilters()
	{
		return getProperties().getClassFilters();
	}

	public void setClassFilters(ClassFilter[] filters)
	{
		if(getProperties().setClassFilters(filters))
		{
			fireBreakpointChanged();
		}
	}

	@Override
	public ClassFilter[] getClassExclusionFilters()
	{
		return getProperties().getClassExclusionFilters();
	}

	public void setClassExclusionFilters(ClassFilter[] filters)
	{
		if(getProperties().setClassExclusionFilters(filters))
		{
			fireBreakpointChanged();
		}
	}

	@Override
	public boolean isInstanceFiltersEnabled()
	{
		return getProperties().isINSTANCE_FILTERS_ENABLED();
	}

	public void setInstanceFiltersEnabled(boolean enabled)
	{
		if(getProperties().setINSTANCE_FILTERS_ENABLED(enabled))
		{
			fireBreakpointChanged();
		}
	}

	@Override
	public InstanceFilter[] getInstanceFilters()
	{
		return getProperties().getInstanceFilters();
	}

	public void setInstanceFilters(InstanceFilter[] filters)
	{
		if(getProperties().setInstanceFilters(filters))
		{
			fireBreakpointChanged();
		}
	}

	private static String getSuspendPolicy(XBreakpoint breakpoint)
	{
		switch(breakpoint.getSuspendPolicy())
		{
			case ALL:
				return DebuggerSettings.SUSPEND_ALL;
			case THREAD:
				return DebuggerSettings.SUSPEND_THREAD;
			case NONE:
				return DebuggerSettings.SUSPEND_NONE;

			default:
				throw new IllegalArgumentException("unknown suspend policy");
		}
	}

	static SuspendPolicy transformSuspendPolicy(String policy)
	{
		if(DebuggerSettings.SUSPEND_ALL.equals(policy))
		{
			return SuspendPolicy.ALL;
		}
		else if(DebuggerSettings.SUSPEND_THREAD.equals(policy))
		{
			return SuspendPolicy.THREAD;
		}
		else if(DebuggerSettings.SUSPEND_NONE.equals(policy))
		{
			return SuspendPolicy.NONE;
		}
		else
		{
			throw new IllegalArgumentException("unknown suspend policy");
		}
	}

	protected boolean isSuspend()
	{
		return myXBreakpoint.getSuspendPolicy() != SuspendPolicy.NONE;
	}

	@Override
	public String getSuspendPolicy()
	{
		return getSuspendPolicy(myXBreakpoint);
	}

	public void setSuspendPolicy(String policy)
	{
		myXBreakpoint.setSuspendPolicy(transformSuspendPolicy(policy));
	}

	public boolean isConditionEnabled()
	{
		XExpression condition = myXBreakpoint.getConditionExpression();
		if(XDebuggerUtil.getInstance().isEmptyExpression(condition))
		{
			return false;
		}
		return !getCondition().isEmpty();
	}

	public void setCondition(@Nullable TextWithImports condition)
	{
		myXBreakpoint.setConditionExpression(TextWithImportsImpl.toXExpression(condition));
	}

	public void addInstanceFilter(long l)
	{
		getProperties().addInstanceFilter(l);
	}

	protected void fireBreakpointChanged()
	{
		myXBreakpoint.fireBreakpointChanged();
	}
}
