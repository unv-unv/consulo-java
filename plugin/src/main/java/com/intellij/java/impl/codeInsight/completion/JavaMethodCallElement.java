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
package com.intellij.java.impl.codeInsight.completion;

import com.intellij.java.impl.codeInsight.completion.util.MethodParenthesesHandler;
import com.intellij.java.impl.codeInsight.lookup.TypedLookupItem;
import com.intellij.java.impl.codeInsight.lookup.impl.JavaElementLookupRenderer;
import com.intellij.java.language.LanguageLevel;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.application.util.registry.Registry;
import consulo.codeEditor.Editor;
import consulo.disposer.Disposer;
import consulo.document.Document;
import consulo.document.event.DocumentAdapter;
import consulo.document.event.DocumentEvent;
import consulo.document.util.TextRange;
import consulo.externalService.statistic.FeatureUsageTracker;
import consulo.ide.impl.idea.codeInsight.completion.CodeCompletionFeatures;
import consulo.language.editor.AutoPopupController;
import consulo.language.editor.WriteCommandAction;
import consulo.language.editor.completion.ClassConditionKey;
import consulo.language.editor.completion.OffsetKey;
import consulo.language.editor.completion.lookup.*;
import consulo.language.editor.template.*;
import consulo.language.editor.template.event.TemplateEditingAdapter;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiReference;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.util.lang.SystemProperties;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author peter
 */
public class JavaMethodCallElement extends LookupItem<PsiMethod> implements TypedLookupItem, StaticallyImportable {
    private static final boolean JAVA_COMPLETION_ARGUMENT_LIVE_TEMPLATE = SystemProperties.getBooleanProperty("java.completion.argument.live.template", false);
    private static final boolean JAVA_COMPLETION_ARGUMENT_LIVE_TEMPLATE_COMPLETION = SystemProperties.getBooleanProperty("java.completion.argument.live.template.completion", false);

    public static final ClassConditionKey<JavaMethodCallElement> CLASS_CONDITION_KEY = ClassConditionKey.create(JavaMethodCallElement.class);
    public static final Key<Boolean> COMPLETION_HINTS = Key.create("completion.hints");

    public static boolean isCompletionMode(@Nonnull PsiCall expression) {
        return expression.getUserData(COMPLETION_HINTS) != null;
    }

    public static int getCompletionHintsLimit() {
        return Math.max(1, Registry.intValue("editor.completion.hints.per.call.limit", 256));
    }

    @Nullable
    private final PsiClass myContainingClass;
    private final PsiMethod myMethod;
    private final MemberLookupHelper myHelper;
    private PsiSubstitutor myQualifierSubstitutor = PsiSubstitutor.EMPTY;
    private PsiSubstitutor myInferenceSubstitutor = PsiSubstitutor.EMPTY;
    private boolean myMayNeedExplicitTypeParameters;
    private String myForcedQualifier = "";

    public JavaMethodCallElement(@Nonnull PsiMethod method) {
        this(method, method.getName());
    }

    public JavaMethodCallElement(@Nonnull PsiMethod method, String methodName) {
        super(method, methodName);
        myMethod = method;
        myHelper = null;
        myContainingClass = method.getContainingClass();
    }

    public JavaMethodCallElement(PsiMethod method, boolean shouldImportStatic, boolean mergedOverloads) {
        super(method, method.getName());
        myMethod = method;
        myContainingClass = method.getContainingClass();
        myHelper = new MemberLookupHelper(method, myContainingClass, shouldImportStatic, mergedOverloads);
        if (!shouldImportStatic) {
            if (myContainingClass != null) {
                String className = myContainingClass.getName();
                if (className != null) {
                    addLookupStrings(className + "." + myMethod.getName());
                }
            }
        }
    }

    void setForcedQualifier(@Nonnull String forcedQualifier) {
        myForcedQualifier = forcedQualifier;
        setLookupString(forcedQualifier + getLookupString());
    }

    @Override
    public PsiType getType() {
        return getSubstitutor().substitute(getInferenceSubstitutor().substitute(getObject().getReturnType()));
    }

    public void setInferenceSubstitutor(@Nonnull final PsiSubstitutor substitutor, PsiElement place) {
        myInferenceSubstitutor = substitutor;
        myMayNeedExplicitTypeParameters = mayNeedTypeParameters(place);
    }

    public JavaMethodCallElement setQualifierSubstitutor(@Nonnull PsiSubstitutor qualifierSubstitutor) {
        myQualifierSubstitutor = qualifierSubstitutor;
        return this;
    }

    @Nonnull
    public PsiSubstitutor getSubstitutor() {
        return myQualifierSubstitutor;
    }

    @Nonnull
    public PsiSubstitutor getInferenceSubstitutor() {
        return myInferenceSubstitutor;
    }

    @Override
    public void setShouldBeImported(boolean shouldImportStatic) {
        myHelper.setShouldBeImported(shouldImportStatic);
    }

    @Override
    public boolean canBeImported() {
        return myHelper != null;
    }

    @Override
    public boolean willBeImported() {
        return canBeImported() && myHelper.willBeImported();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JavaMethodCallElement)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        return myInferenceSubstitutor.equals(((JavaMethodCallElement) o).myInferenceSubstitutor);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + myInferenceSubstitutor.hashCode();
        return result;
    }

    @Override
    public void handleInsert(InsertionContext context) {
        final Document document = context.getDocument();
        final PsiFile file = context.getFile();
        final PsiMethod method = getObject();

        final LookupElement[] allItems = context.getElements();
        final boolean overloadsMatter = allItems.length == 1 && getUserData(JavaCompletionUtil.FORCE_SHOW_SIGNATURE_ATTR) == null;
        final boolean hasParams = MethodParenthesesHandler.hasParams(this, allItems, overloadsMatter, method);
        JavaCompletionUtil.insertParentheses(context, this, overloadsMatter, hasParams);

        final int startOffset = context.getStartOffset();
        final OffsetKey refStart = context.trackOffset(startOffset, true);
        if (shouldInsertTypeParameters() && mayNeedTypeParameters(context.getFile().findElementAt(context.getStartOffset()))) {
            qualifyMethodCall(file, startOffset, document);
            insertExplicitTypeParameters(context, refStart);
        }
        else if (myHelper != null) {
            context.commitDocument();
            importOrQualify(document, file, method, startOffset);
        }

        PsiCallExpression methodCall = findCallAtOffset(context, context.getOffset(refStart));
        if (methodCall != null) {
            CompletionMemory.registerChosenMethod(method, methodCall);
            handleNegation(context, document, method, methodCall);
        }

        startArgumentLiveTemplate(context, method);
    }

    static PsiCallExpression findCallAtOffset(InsertionContext context, int offset) {
        context.commitDocument();
        return PsiTreeUtil.findElementOfClassAtOffset(context.getFile(), offset, PsiCallExpression.class, false);
    }

    private static void handleNegation(InsertionContext context, Document document, PsiMethod method, PsiCallExpression methodCall) {
        PsiType type = method.getReturnType();
        if (context.getCompletionChar() == '!' && type != null && PsiType.BOOLEAN.isAssignableFrom(type)) {
            context.setAddCompletionChar(false);
            FeatureUsageTracker.getInstance().triggerFeatureUsed(CodeCompletionFeatures.EXCLAMATION_FINISH);
            document.insertString(methodCall.getTextRange().getStartOffset(), "!");
        }
    }

    private void importOrQualify(Document document, PsiFile file, PsiMethod method, int startOffset) {
        if (willBeImported()) {
            final PsiReferenceExpression ref = PsiTreeUtil.findElementOfClassAtOffset(file, startOffset, PsiReferenceExpression.class, false);
            if (ref != null && myContainingClass != null && !ref.isReferenceTo(method)) {
                ref.bindToElementViaStaticImport(myContainingClass);
            }
            return;
        }

        qualifyMethodCall(file, startOffset, document);
    }

    public static final Key<PsiMethod> ARGUMENT_TEMPLATE_ACTIVE = Key.create("ARGUMENT_TEMPLATE_ACTIVE");

    @Nonnull
    private static Template createArgTemplate(PsiMethod method, int caretOffset, PsiExpressionList argList, TextRange argRange) {
        Template template = TemplateManager.getInstance(method.getProject()).createTemplate("", "");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                template.addTextSegment(", ");
            }
            String name = StringUtil.notNullize(parameters[i].getName());
            Expression expression = JAVA_COMPLETION_ARGUMENT_LIVE_TEMPLATE_COMPLETION ? new AutoPopupCompletion() : new ConstantNode(name);
            template.addVariable(name, expression, new ConstantNode(name), true);
        }
        boolean finishInsideParens = method.isVarArgs();
        if (finishInsideParens) {
            template.addEndVariable();
        }
        template.addTextSegment(argList.getText().substring(caretOffset - argRange.getStartOffset(), argList.getTextLength()));
        if (!finishInsideParens) {
            template.addEndVariable();
        }
        return template;
    }

    public static boolean startArgumentLiveTemplate(InsertionContext context, PsiMethod method) {
        if (method.getParameterList().getParametersCount() == 0 || context.getCompletionChar() == Lookup.COMPLETE_STATEMENT_SELECT_CHAR || !JAVA_COMPLETION_ARGUMENT_LIVE_TEMPLATE) {
            return false;
        }

        Editor editor = context.getEditor();
        context.commitDocument();
        PsiCall call = PsiTreeUtil.findElementOfClassAtOffset(context.getFile(), context.getStartOffset(), PsiCall.class, false);
        PsiExpressionList argList = call == null ? null : call.getArgumentList();
        if (argList == null || argList.getExpressions().length > 0) {
            return false;
        }

        TextRange argRange = argList.getTextRange();
        int caretOffset = editor.getCaretModel().getOffset();
        if (!argRange.contains(caretOffset)) {
            return false;
        }

        Template template = createArgTemplate(method, caretOffset, argList, argRange);

        context.getDocument().deleteString(caretOffset, argRange.getEndOffset());
        TemplateManager.getInstance(method.getProject()).startTemplate(editor, template);

        TemplateState templateState = TemplateManager.getInstance(method.getProject()).getTemplateState(editor);
        if (templateState == null) {
            return false;
        }

        setupNonFilledArgumentRemoving(editor, templateState);

        editor.putUserData(ARGUMENT_TEMPLATE_ACTIVE, method);
        Disposer.register(templateState, () ->
        {
            if (editor.getUserData(ARGUMENT_TEMPLATE_ACTIVE) == method) {
                editor.putUserData(ARGUMENT_TEMPLATE_ACTIVE, null);
            }
        });
        return true;
    }

    private static void setupNonFilledArgumentRemoving(final Editor editor, final TemplateState templateState) {
        AtomicInteger maxEditedVariable = new AtomicInteger(-1);
        editor.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            public void documentChanged(DocumentEvent e) {
                maxEditedVariable.set(Math.max(maxEditedVariable.get(), templateState.getCurrentVariableNumber()));
            }
        }, templateState);

        templateState.addTemplateStateListener(new TemplateEditingAdapter() {
            @Override
            public void currentVariableChanged(TemplateState templateState, Template template, int oldIndex, int newIndex) {
                maxEditedVariable.set(Math.max(maxEditedVariable.get(), oldIndex));
            }

            @Override
            public void beforeTemplateFinished(TemplateState state, Template template, boolean brokenOff) {
                if (brokenOff) {
                    removeUntouchedArguments(template);
                }
            }

            private void removeUntouchedArguments(Template template) {
                int firstUnchangedVar = maxEditedVariable.get() + 1;
                if (firstUnchangedVar >= template.getVariableCount()) {
                    return;
                }

                TextRange startRange = templateState.getVariableRange(template.getVariableNameAt(firstUnchangedVar));
                TextRange endRange = templateState.getVariableRange(template.getVariableNameAt(template.getVariableCount() - 1));
                if (startRange == null || endRange == null) {
                    return;
                }

                WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> editor.getDocument().deleteString(startRange.getStartOffset(), endRange.getEndOffset()));
            }
        });
    }

    private boolean shouldInsertTypeParameters() {
        return myMayNeedExplicitTypeParameters && !getInferenceSubstitutor().equals(PsiSubstitutor.EMPTY) && myMethod.getParameterList().getParametersCount() == 0;
    }

    public static boolean mayNeedTypeParameters(@Nullable final PsiElement leaf) {
        if (PsiTreeUtil.getParentOfType(leaf, PsiExpressionList.class, true, PsiCodeBlock.class, PsiModifierListOwner.class) == null) {
            if (PsiTreeUtil.getParentOfType(leaf, PsiConditionalExpression.class, true, PsiCodeBlock.class, PsiModifierListOwner.class) == null) {
                return false;
            }
        }

        if (PsiUtil.getLanguageLevel(leaf).isAtLeast(LanguageLevel.JDK_1_8)) {
            return false;
        }

        final PsiElement parent = leaf.getParent();
        if (parent instanceof PsiReferenceExpression && ((PsiReferenceExpression) parent).getTypeParameters().length > 0) {
            return false;
        }
        return true;
    }

    private void insertExplicitTypeParameters(InsertionContext context, OffsetKey refStart) {
        context.commitDocument();

        final String typeParams = getTypeParamsText(false);
        if (typeParams != null) {
            context.getDocument().insertString(context.getOffset(refStart), typeParams);
            JavaCompletionUtil.shortenReference(context.getFile(), context.getOffset(refStart));
        }
    }

    private void qualifyMethodCall(PsiFile file, final int startOffset, final Document document) {
        final PsiReference reference = file.findReferenceAt(startOffset);
        if (reference instanceof PsiReferenceExpression && ((PsiReferenceExpression) reference).isQualified()) {
            return;
        }

        final PsiMethod method = getObject();
        if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            document.insertString(startOffset, "this.");
            return;
        }

        if (myContainingClass == null) {
            return;
        }

        document.insertString(startOffset, ".");
        JavaCompletionUtil.insertClassReference(myContainingClass, file, startOffset);
    }

    @Nullable
    private String getTypeParamsText(boolean presentable) {
        final PsiMethod method = getObject();
        final PsiSubstitutor substitutor = getInferenceSubstitutor();
        final PsiTypeParameter[] parameters = method.getTypeParameters();
        assert parameters.length > 0;
        final StringBuilder builder = new StringBuilder("<");
        boolean first = true;
        for (final PsiTypeParameter parameter : parameters) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            PsiType type = substitutor.substitute(parameter);
            if (type instanceof PsiWildcardType) {
                type = ((PsiWildcardType) type).getExtendsBound();
            }

            if (type == null || type instanceof PsiCapturedWildcardType) {
                return null;
            }
            if (type.equals(TypeConversionUtil.typeParameterErasure(parameter))) {
                return null;
            }

            final String text = presentable ? type.getPresentableText() : type.getCanonicalText();
            if (text.indexOf('?') >= 0) {
                return null;
            }

            builder.append(text);
        }
        return builder.append(">").toString();
    }

    @Override
    public boolean isValid() {
        return super.isValid() && myInferenceSubstitutor.isValid() && getSubstitutor().isValid();
    }

    @Override
    public void renderElement(LookupElementPresentation presentation) {
        presentation.setIcon(DefaultLookupItemRenderer.getRawIcon(this, presentation.isReal()));

        presentation.setStrikeout(JavaElementLookupRenderer.isToStrikeout(this));

        MemberLookupHelper helper = myHelper != null ? myHelper : new MemberLookupHelper(myMethod, myContainingClass, false, false);
        helper.renderElement(presentation, myHelper != null, myHelper != null && !myHelper.willBeImported(), getSubstitutor());
        if (!myForcedQualifier.isEmpty()) {
            presentation.setItemText(myForcedQualifier + presentation.getItemText());
        }

        if (shouldInsertTypeParameters()) {
            String typeParamsText = getTypeParamsText(true);
            if (typeParamsText != null) {
                if (typeParamsText.length() > 10) {
                    typeParamsText = typeParamsText.substring(0, 10) + "...>";
                }

                String itemText = presentation.getItemText();
                assert itemText != null;
                int i = itemText.indexOf('.');
                if (i > 0) {
                    presentation.setItemText(itemText.substring(0, i + 1) + typeParamsText + itemText.substring(i + 1));
                }
            }
        }

    }

    private static class AutoPopupCompletion extends Expression {
        @Nullable
        @Override
        public Result calculateResult(ExpressionContext context) {
            return new InvokeActionResult(() -> AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor()));
        }

        @Nullable
        @Override
        public Result calculateQuickResult(ExpressionContext context) {
            return null;
        }

        @Nullable
        @Override
        public LookupElement[] calculateLookupItems(ExpressionContext context) {
            return null;
        }
    }
}
