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
package com.intellij.java.impl.codeInsight.generation;

import com.intellij.java.analysis.impl.codeInsight.intention.AddAnnotationFix;
import com.intellij.java.analysis.impl.codeInsight.intention.AddAnnotationPsiFix;
import com.intellij.java.impl.codeInsight.MethodImplementor;
import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.codeInsight.AnnotationUtil;
import com.intellij.java.language.impl.codeInsight.generation.OverrideImplementExploreUtil;
import com.intellij.java.language.impl.codeInsight.template.JavaTemplateUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.infos.CandidateInfo;
import com.intellij.java.language.psi.javadoc.PsiDocComment;
import com.intellij.java.language.psi.util.*;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.access.RequiredWriteAction;
import consulo.application.Result;
import consulo.codeEditor.Editor;
import consulo.codeEditor.ScrollType;
import consulo.externalService.statistic.FeatureUsageTracker;
import consulo.fileEditor.FileEditorManager;
import consulo.fileTemplate.FileTemplate;
import consulo.fileTemplate.FileTemplateManager;
import consulo.fileTemplate.FileTemplateUtil;
import consulo.ide.impl.idea.ide.util.MemberChooser;
import consulo.language.codeStyle.CodeStyleManager;
import consulo.language.codeStyle.CodeStyleSettingsManager;
import consulo.language.codeStyle.CommonCodeStyleSettings;
import consulo.language.editor.WriteCommandAction;
import consulo.language.editor.action.CodeInsightActionHandler;
import consulo.language.editor.localize.CodeInsightLocalize;
import consulo.language.editor.util.ProductivityFeatureNames;
import consulo.language.file.FileTypeManager;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.module.Module;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.KeyboardShortcut;
import consulo.ui.ex.action.Shortcut;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.keymap.Keymap;
import consulo.ui.ex.keymap.KeymapManager;
import consulo.undoRedo.CommandProcessor;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.fileType.FileType;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.function.Consumer;

public class OverrideImplementUtil extends OverrideImplementExploreUtil {
    private static final Logger LOG = Logger.getInstance(OverrideImplementUtil.class);

    private OverrideImplementUtil() {
    }

    /**
     * generate methods (with bodies) corresponding to given method declaration
     * there are maybe two method implementations for one declaration
     * (e.g. EJB' create() -> ejbCreate(), ejbPostCreate() )
     *
     * @param aClass        context for method implementations
     * @param method        method to override or implement
     * @param toCopyJavaDoc true if copy JavaDoc from method declaration
     * @return list of method prototypes
     */
    @Nonnull
    @RequiredWriteAction
    public static List<PsiMethod> overrideOrImplementMethod(PsiClass aClass, PsiMethod method, boolean toCopyJavaDoc)
        throws IncorrectOperationException {
        PsiClass containingClass = method.getContainingClass();
        LOG.assertTrue(containingClass != null);
        PsiSubstitutor substitutor = aClass.isInheritor(containingClass, true)
            ? TypeConversionUtil.getSuperClassSubstitutor(containingClass, aClass, PsiSubstitutor.EMPTY)
            : PsiSubstitutor.EMPTY;
        return overrideOrImplementMethod(
            aClass,
            method,
            substitutor,
            toCopyJavaDoc,
            CodeStyleSettingsManager.getSettings(aClass.getProject()).INSERT_OVERRIDE_ANNOTATION
        );
    }

    @RequiredReadAction
    public static boolean isInsertOverride(PsiMethod superMethod, PsiClass targetClass) {
        return CodeStyleSettingsManager.getSettings(targetClass.getProject()).INSERT_OVERRIDE_ANNOTATION
            && canInsertOverride(superMethod, targetClass);
    }

    @RequiredReadAction
    public static boolean canInsertOverride(PsiMethod superMethod, PsiClass targetClass) {
        if (superMethod.isConstructor() || superMethod.isStatic()) {
            return false;
        }
        if (!PsiUtil.isLanguageLevel5OrHigher(targetClass)) {
            return false;
        }
        if (PsiUtil.isLanguageLevel6OrHigher(targetClass)) {
            return true;
        }
        PsiClass superClass = superMethod.getContainingClass();
        return superClass != null && !superClass.isInterface();
    }

    @RequiredWriteAction
    public static List<PsiMethod> overrideOrImplementMethod(
        PsiClass aClass,
        PsiMethod method,
        PsiSubstitutor substitutor,
        boolean toCopyJavaDoc,
        boolean insertOverrideIfPossible
    ) throws IncorrectOperationException {
        if (!method.isValid() || !substitutor.isValid()) {
            return Collections.emptyList();
        }

        List<PsiMethod> results = new ArrayList<>();
        aClass.getApplication().getExtensionPoint(MethodImplementor.class).forEach(implementor -> {
            PsiMethod[] prototypes = implementor.createImplementationPrototypes(aClass, method);
            for (PsiMethod prototype : prototypes) {
                implementor.createDecorator(aClass, method, toCopyJavaDoc, insertOverrideIfPossible).accept(prototype);
                results.add(prototype);
            }
        });

        if (results.isEmpty()) {
            PsiMethod method1 = GenerateMembersUtil.substituteGenericMethod(method, substitutor, aClass);

            PsiElementFactory factory = JavaPsiFacade.getInstance(method.getProject()).getElementFactory();
            PsiMethod result = (PsiMethod)factory.createClass("Dummy").add(method1);
            if (PsiUtil.isAnnotationMethod(result)) {
                PsiAnnotationMemberValue defaultValue = ((PsiAnnotationMethod)result).getDefaultValue();
                if (defaultValue != null) {
                    PsiElement defaultKeyword = defaultValue;
                    while (!(defaultKeyword instanceof PsiKeyword) && defaultKeyword != null) {
                        defaultKeyword = defaultKeyword.getPrevSibling();
                    }
                    if (defaultKeyword == null) {
                        defaultKeyword = defaultValue;
                    }
                    defaultValue.getParent().deleteChildRange(defaultKeyword, defaultValue);
                }
            }
            Consumer<PsiMethod> decorator = createDefaultDecorator(aClass, method, toCopyJavaDoc, insertOverrideIfPossible);
            decorator.accept(result);
            results.add(result);
        }

        for (Iterator<PsiMethod> iterator = results.iterator(); iterator.hasNext(); ) {
            if (aClass.findMethodBySignature(iterator.next(), false) != null) {
                iterator.remove();
            }
        }

        return results;
    }

    public static Consumer<PsiMethod> createDefaultDecorator(
        PsiClass aClass,
        PsiMethod method,
        boolean toCopyJavaDoc,
        boolean insertOverrideIfPossible
    ) {
        return result -> decorateMethod(aClass, method, toCopyJavaDoc, insertOverrideIfPossible, result);
    }

    @RequiredWriteAction
    private static PsiMethod decorateMethod(
        PsiClass aClass,
        PsiMethod method,
        boolean toCopyJavaDoc,
        boolean insertOverrideIfPossible,
        PsiMethod result
    ) {
        PsiUtil.setModifierProperty(result, PsiModifier.ABSTRACT, aClass.isInterface() && method.isAbstract());
        PsiUtil.setModifierProperty(result, PsiModifier.NATIVE, false);

        if (!toCopyJavaDoc) {
            deleteDocComment(result);
        }

        //method type params are not allowed when overriding from raw type
        PsiTypeParameterList list = result.getTypeParameterList();
        if (list != null) {
            PsiClass containingClass = method.getContainingClass();
            if (containingClass != null) {
                for (PsiClassType classType : aClass.getSuperTypes()) {
                    if (InheritanceUtil.isInheritorOrSelf(PsiUtil.resolveClassInType(classType), containingClass, true)
                        && classType.isRaw()) {
                        list.replace(JavaPsiFacade.getElementFactory(aClass.getProject()).createTypeParameterList());
                        break;
                    }
                }
            }
        }

        annotateOnOverrideImplement(result, aClass, method, insertOverrideIfPossible);

        if (CodeStyleSettingsManager.getSettings(aClass.getProject()).REPEAT_SYNCHRONIZED
            && method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
            result.getModifierList().setModifierProperty(PsiModifier.SYNCHRONIZED, true);
        }

        PsiCodeBlock body = JavaPsiFacade.getInstance(method.getProject()).getElementFactory().createCodeBlockFromText("{}", null);
        PsiCodeBlock oldBody = result.getBody();
        if (oldBody != null) {
            oldBody.replace(body);
        }
        else {
            result.add(body);
        }

        setupMethodBody(result, method, aClass);

        // probably, it's better to reformat the whole method - it can go from other style sources
        Project project = method.getProject();
        CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
        CommonCodeStyleSettings javaSettings = CodeStyleSettingsManager.getSettings(project).getCommonSettings(JavaLanguage.INSTANCE);
        boolean keepBreaks = javaSettings.KEEP_LINE_BREAKS;
        javaSettings.KEEP_LINE_BREAKS = false;
        result = (PsiMethod)JavaCodeStyleManager.getInstance(project).shortenClassReferences(result);
        result = (PsiMethod)codeStyleManager.reformat(result);
        javaSettings.KEEP_LINE_BREAKS = keepBreaks;
        return result;
    }

    public static void deleteDocComment(PsiMethod result) {
        PsiDocComment comment = result.getDocComment();
        if (comment != null) {
            comment.delete();
        }
    }

    @RequiredWriteAction
    public static void annotateOnOverrideImplement(PsiMethod method, PsiClass targetClass, PsiMethod overridden) {
        annotateOnOverrideImplement(
            method,
            targetClass,
            overridden,
            CodeStyleSettingsManager.getSettings(method.getProject()).INSERT_OVERRIDE_ANNOTATION
        );
    }

    @RequiredWriteAction
    public static void annotateOnOverrideImplement(PsiMethod method, PsiClass targetClass, PsiMethod overridden, boolean insertOverride) {
        if (insertOverride && canInsertOverride(overridden, targetClass)) {
            String overrideAnnotationName = Override.class.getName();
            if (!AnnotationUtil.isAnnotated(method, overrideAnnotationName, false, true)) {
                AddAnnotationPsiFix.addPhysicalAnnotationTo(overrideAnnotationName, PsiNameValuePair.EMPTY_ARRAY, method.getModifierList());
            }
        }
        Module module = targetClass.getModule();
        GlobalSearchScope moduleScope = module != null ? GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module) : null;
        Project project = targetClass.getProject();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        project.getApplication().getExtensionPoint(OverrideImplementsAnnotationsHandler.class).forEach(each -> {
            for (String annotation : each.getAnnotations(project)) {
                if (moduleScope != null && facade.findClass(annotation, moduleScope) == null) {
                    continue;
                }
                if (AnnotationUtil.isAnnotated(overridden, annotation, false, false)
                    && !AnnotationUtil.isAnnotated(method, annotation, false, false)) {
                    PsiAnnotation psiAnnotation = AnnotationUtil.findAnnotation(overridden, annotation);
                    if (psiAnnotation != null && AnnotationUtil.isInferredAnnotation(psiAnnotation)) {
                        return;
                    }

                    AddAnnotationPsiFix.removePhysicalAnnotations(method, each.annotationsToRemove(project, annotation));
                    AddAnnotationPsiFix.addPhysicalAnnotationTo(annotation, PsiNameValuePair.EMPTY_ARRAY, method.getModifierList());
                }
            }
        });
    }

    public static void annotate(@Nonnull PsiMethod result, String fqn, String... annosToRemove) throws IncorrectOperationException {
        Project project = result.getProject();
        AddAnnotationFix fix = new AddAnnotationFix(fqn, result, annosToRemove);
        if (fix.isAvailable(project, null, result.getContainingFile())) {
            fix.invoke(project, null, result.getContainingFile());
        }
    }

    @Nonnull
    @RequiredWriteAction
    public static List<PsiGenerationInfo<PsiMethod>> overrideOrImplementMethods(
        PsiClass aClass,
        Collection<PsiMethodMember> candidates,
        boolean toCopyJavaDoc,
        boolean toInsertAtOverride
    ) throws IncorrectOperationException {
        List<CandidateInfo> candidateInfos = ContainerUtil.map2List(candidates, s -> new CandidateInfo(s.getElement(), s.getSubstitutor()));
        List<PsiMethod> methods = overrideOrImplementMethodCandidates(aClass, candidateInfos, toCopyJavaDoc, toInsertAtOverride);
        return convert2GenerationInfos(methods);
    }

    @Nonnull
    @RequiredWriteAction
    public static List<PsiMethod> overrideOrImplementMethodCandidates(
        PsiClass aClass,
        Collection<CandidateInfo> candidates,
        boolean toCopyJavaDoc,
        boolean insertOverrideWherePossible
    ) throws IncorrectOperationException {
        List<PsiMethod> result = new ArrayList<>();
        for (CandidateInfo candidateInfo : candidates) {
            result.addAll(overrideOrImplementMethod(
                aClass,
                (PsiMethod)candidateInfo.getElement(),
                candidateInfo.getSubstitutor(),
                toCopyJavaDoc,
                insertOverrideWherePossible
            ));
        }
        return result;
    }

    public static List<PsiGenerationInfo<PsiMethod>> convert2GenerationInfos(Collection<PsiMethod> methods) {
        return ContainerUtil.map2List(methods, OverrideImplementUtil::createGenerationInfo);
    }

    public static PsiGenerationInfo<PsiMethod> createGenerationInfo(PsiMethod s) {
        return createGenerationInfo(s, true);
    }

    public static PsiGenerationInfo<PsiMethod> createGenerationInfo(PsiMethod s, boolean mergeIfExists) {
        PsiGenerationInfo generationInfo = s.getApplication().getExtensionPoint(MethodImplementor.class).computeSafeIfAny(
            implementor -> implementor.createGenerationInfo(s, mergeIfExists) instanceof PsiGenerationInfo genInfo ? genInfo : null
        );
        return generationInfo != null ? generationInfo : new PsiGenerationInfo<>(s);
    }

    @Nonnull
    public static String callSuper(PsiMethod superMethod, PsiMethod overriding) {
        StringBuilder buffer = new StringBuilder();
        if (!superMethod.isConstructor() && !PsiType.VOID.equals(superMethod.getReturnType())) {
            buffer.append("return ");
        }
        buffer.append("super");
        PsiParameter[] parameters = overriding.getParameterList().getParameters();
        if (!superMethod.isConstructor()) {
            buffer.append(".");
            buffer.append(superMethod.getName());
        }
        buffer.append("(");
        for (int i = 0; i < parameters.length; i++) {
            String name = parameters[i].getName();
            if (i > 0) {
                buffer.append(",");
            }
            buffer.append(name);
        }
        buffer.append(")");
        return buffer.toString();
    }

    @RequiredWriteAction
    public static void setupMethodBody(PsiMethod result, PsiMethod originalMethod, PsiClass targetClass)
        throws IncorrectOperationException {
        boolean isAbstract = originalMethod.isAbstract() || originalMethod.hasModifierProperty(PsiModifier.DEFAULT);
        String templateName =
            isAbstract ? JavaTemplateUtil.TEMPLATE_IMPLEMENTED_METHOD_BODY : JavaTemplateUtil.TEMPLATE_OVERRIDDEN_METHOD_BODY;
        FileTemplate template = FileTemplateManager.getInstance(result.getProject()).getCodeTemplate(templateName);
        setupMethodBody(result, originalMethod, targetClass, template);
    }

    @RequiredWriteAction
    public static void setupMethodBody(PsiMethod result, PsiMethod originalMethod, PsiClass targetClass, FileTemplate template)
        throws IncorrectOperationException {
        if (targetClass.isInterface()) {
            if (isImplementInterfaceInJava8Interface(targetClass) || originalMethod.hasModifierProperty(PsiModifier.DEFAULT)) {
                PsiUtil.setModifierProperty(result, PsiModifier.DEFAULT, true);
            }
            else {
                PsiCodeBlock body = result.getBody();
                if (body != null) {
                    body.delete();
                }
            }
        }
        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension(template.getExtension());
        PsiType returnType = result.getReturnType();
        if (returnType == null) {
            returnType = PsiType.VOID;
        }
        Properties properties = FileTemplateManager.getInstance(result.getProject()).getDefaultProperties();
        properties.setProperty(FileTemplate.ATTRIBUTE_RETURN_TYPE, returnType.getPresentableText());
        properties.setProperty(FileTemplate.ATTRIBUTE_DEFAULT_RETURN_VALUE, PsiTypesUtil.getDefaultValueOfType(returnType));
        properties.setProperty(FileTemplate.ATTRIBUTE_CALL_SUPER, callSuper(originalMethod, result));
        JavaTemplateUtil.setClassAndMethodNameProperties(properties, targetClass, result);

        JVMElementFactory factory = JVMElementFactories.getFactory(targetClass.getLanguage(), originalMethod.getProject());
        if (factory == null) {
            factory = JavaPsiFacade.getInstance(originalMethod.getProject()).getElementFactory();
        }
        String methodText;

        try {
            methodText = "void foo () {\n" + template.getText(properties) + "\n}";
            methodText = FileTemplateUtil.indent(methodText, result.getProject(), fileType);
        }
        catch (Exception e) {
            throw new IncorrectOperationException("Failed to parse file template", e);
        }
        if (methodText != null) {
            PsiMethod m;
            try {
                m = factory.createMethodFromText(methodText, originalMethod);
            }
            catch (IncorrectOperationException e) {
                targetClass.getApplication().invokeLater(() -> Messages.showErrorDialog(
                    CodeInsightLocalize.overrideImplementBrokenFileTemplateMessage().get(),
                    CodeInsightLocalize.overrideImplementBrokenFileTemplateTitle().get()
                ));
                return;
            }
            PsiCodeBlock oldBody = result.getBody();
            if (oldBody != null) {
                oldBody.replace(m.getBody());
            }
        }
    }

    @RequiredReadAction
    private static boolean isImplementInterfaceInJava8Interface(PsiClass targetClass) {
        if (!PsiUtil.isLanguageLevel8OrHigher(targetClass)) {
            return false;
        }
        String commandName = CommandProcessor.getInstance().getCurrentCommandName();
        return commandName != null && StringUtil.containsIgnoreCase(commandName, "implement");
    }

    @RequiredUIAccess
    public static void chooseAndOverrideMethods(Project project, Editor editor, PsiClass aClass) {
        FeatureUsageTracker.getInstance().triggerFeatureUsed(ProductivityFeatureNames.CODEASSISTS_OVERRIDE_IMPLEMENT);
        chooseAndOverrideOrImplementMethods(project, editor, aClass, false);
    }

    @RequiredUIAccess
    public static void chooseAndImplementMethods(Project project, Editor editor, PsiClass aClass) {
        FeatureUsageTracker.getInstance().triggerFeatureUsed(ProductivityFeatureNames.CODEASSISTS_OVERRIDE_IMPLEMENT);
        chooseAndOverrideOrImplementMethods(project, editor, aClass, true);
    }

    @RequiredUIAccess
    public static void chooseAndOverrideOrImplementMethods(Project project, Editor editor, PsiClass aClass, boolean toImplement) {
        LOG.assertTrue(aClass.isValid());
        project.getApplication().assertReadAccessAllowed();

        Collection<CandidateInfo> candidates = getMethodsToOverrideImplement(aClass, toImplement);
        Collection<CandidateInfo> secondary = toImplement || aClass.isInterface()
            ? new ArrayList<>()
            : getMethodsToOverrideImplement(aClass, true);

        MemberChooser<PsiMethodMember> chooser = showOverrideImplementChooser(editor, aClass, toImplement, candidates, secondary);
        if (chooser == null) {
            return;
        }

        List<PsiMethodMember> selectedElements = chooser.getSelectedElements();
        if (selectedElements == null || selectedElements.isEmpty()) {
            return;
        }

        LOG.assertTrue(aClass.isValid());
        new WriteCommandAction(project, aClass.getContainingFile()) {
            @Override
            @RequiredUIAccess
            protected void run(@Nonnull Result result) throws Throwable {
                overrideOrImplementMethodsInRightPlace(
                    editor,
                    aClass,
                    selectedElements,
                    chooser.isCopyJavadoc(),
                    chooser.isInsertOverrideAnnotation()
                );
            }
        }.execute();
    }

    /**
     * @param candidates, secondary should allow modifications
     */
    @Nullable
    @RequiredUIAccess
    public static MemberChooser<PsiMethodMember> showOverrideImplementChooser(
        Editor editor,
        PsiElement aClass,
        boolean toImplement,
        Collection<CandidateInfo> candidates,
        Collection<CandidateInfo> secondary
    ) {
        if (toImplement) {
            for (Iterator<CandidateInfo> iterator = candidates.iterator(); iterator.hasNext(); ) {
                CandidateInfo candidate = iterator.next();
                PsiElement element = candidate.getElement();
                if (element instanceof PsiMethod method && method.hasModifierProperty(PsiModifier.DEFAULT)) {
                    iterator.remove();
                    secondary.add(candidate);
                }
            }
        }

        JavaOverrideImplementMemberChooser chooser =
            JavaOverrideImplementMemberChooser.create(aClass, toImplement, candidates, secondary);
        if (chooser == null) {
            return null;
        }
        Project project = aClass.getProject();
        registerHandlerForComplementaryAction(project, editor, aClass, toImplement, chooser);

        if (project.getApplication().isUnitTestMode()) {
            return chooser;
        }
        chooser.show();
        if (chooser.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
            return null;
        }

        return chooser;
    }

    @RequiredUIAccess
    private static void registerHandlerForComplementaryAction(
        Project project,
        Editor editor,
        PsiElement aClass,
        boolean toImplement,
        MemberChooser<PsiMethodMember> chooser
    ) {
        JComponent preferredFocusedComponent = chooser.getPreferredFocusedComponent();
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();

        String s = toImplement ? "OverrideMethods" : "ImplementMethods";
        Shortcut[] shortcuts = keymap.getShortcuts(s);

        if (shortcuts.length > 0 && shortcuts[0] instanceof KeyboardShortcut keyboardShortcut) {
            preferredFocusedComponent.getInputMap().put(keyboardShortcut.getFirstKeyStroke(), s);

            preferredFocusedComponent.getActionMap().put(s, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    chooser.close(DialogWrapper.CANCEL_EXIT_CODE);

                    // invoke later in order to close previous modal dialog
                    project.getApplication().invokeLater(() -> {
                        CodeInsightActionHandler handler =
                            toImplement ? new JavaOverrideMethodsHandler() : new JavaImplementMethodsHandler();
                        handler.invoke(project, editor, aClass.getContainingFile());
                    });
                }
            });
        }
    }

    @RequiredUIAccess
    public static void overrideOrImplementMethodsInRightPlace(
        Editor editor,
        PsiClass aClass,
        Collection<PsiMethodMember> candidates,
        boolean copyJavadoc,
        boolean insertOverrideWherePossible
    ) {
        try {
            int offset = editor.getCaretModel().getOffset();
            PsiElement brace = aClass.getLBrace();
            if (brace == null) {
                PsiClass psiClass = JavaPsiFacade.getInstance(aClass.getProject()).getElementFactory().createClass("X");
                brace = aClass.addRangeAfter(psiClass.getLBrace(), psiClass.getRBrace(), aClass.getLastChild());
                LOG.assertTrue(brace != null, aClass.getLastChild());
            }

            int lbraceOffset = brace.getTextOffset();
            List<PsiGenerationInfo<PsiMethod>> resultMembers;
            if (offset <= lbraceOffset || aClass.isEnum()) {
                resultMembers = new ArrayList<>();
                for (PsiMethodMember candidate : candidates) {
                    Collection<PsiMethod> prototypes = overrideOrImplementMethod(
                        aClass,
                        candidate.getElement(),
                        candidate.getSubstitutor(),
                        copyJavadoc,
                        insertOverrideWherePossible
                    );
                    List<PsiGenerationInfo<PsiMethod>> infos = convert2GenerationInfos(prototypes);
                    for (PsiGenerationInfo<PsiMethod> info : infos) {
                        PsiElement anchor =
                            getDefaultAnchorToOverrideOrImplement(aClass, candidate.getElement(), candidate.getSubstitutor());
                        info.insert(aClass, anchor, true);
                        resultMembers.add(info);
                    }
                }
            }
            else {
                List<PsiGenerationInfo<PsiMethod>> prototypes =
                    overrideOrImplementMethods(aClass, candidates, copyJavadoc, insertOverrideWherePossible);
                resultMembers = GenerateMembersUtil.insertMembersAtOffset(aClass.getContainingFile(), offset, prototypes);
            }

            if (!resultMembers.isEmpty()) {
                resultMembers.get(0).positionCaret(editor, true);
            }
        }
        catch (IncorrectOperationException e) {
            LOG.error(e);
        }
    }

    @Nullable
    @RequiredReadAction
    public static PsiElement getDefaultAnchorToOverrideOrImplement(PsiClass aClass, PsiMethod baseMethod, PsiSubstitutor substitutor) {
        PsiMethod prevBaseMethod = PsiTreeUtil.getPrevSiblingOfType(baseMethod, PsiMethod.class);
        while (prevBaseMethod != null) {
            String name = prevBaseMethod.isConstructor() ? aClass.getName() : prevBaseMethod.getName();
            //Happens when aClass instanceof PsiAnonymousClass
            if (name != null) {
                MethodSignature signature = MethodSignatureUtil.createMethodSignature(
                    name,
                    prevBaseMethod.getParameterList(),
                    prevBaseMethod.getTypeParameterList(),
                    substitutor,
                    prevBaseMethod.isConstructor()
                );
                PsiMethod prevMethod = MethodSignatureUtil.findMethodBySignature(aClass, signature, false);
                if (prevMethod != null && prevMethod.isPhysical()) {
                    return prevMethod.getNextSibling();
                }
            }
            prevBaseMethod = PsiTreeUtil.getPrevSiblingOfType(prevBaseMethod, PsiMethod.class);
        }

        PsiMethod nextBaseMethod = PsiTreeUtil.getNextSiblingOfType(baseMethod, PsiMethod.class);
        while (nextBaseMethod != null) {
            String name = nextBaseMethod.isConstructor() ? aClass.getName() : nextBaseMethod.getName();
            if (name != null) {
                MethodSignature signature = MethodSignatureUtil.createMethodSignature(
                    name,
                    nextBaseMethod.getParameterList(),
                    nextBaseMethod.getTypeParameterList(),
                    substitutor,
                    nextBaseMethod.isConstructor()
                );
                PsiMethod nextMethod = MethodSignatureUtil.findMethodBySignature(aClass, signature, false);
                if (nextMethod != null && nextMethod.isPhysical()) {
                    return nextMethod;
                }
            }
            nextBaseMethod = PsiTreeUtil.getNextSiblingOfType(nextBaseMethod, PsiMethod.class);
        }

        return null;
    }

    @RequiredWriteAction
    public static List<PsiGenerationInfo<PsiMethod>> overrideOrImplement(PsiClass psiClass, @Nonnull PsiMethod baseMethod)
        throws IncorrectOperationException {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(baseMethod.getProject());
        List<PsiGenerationInfo<PsiMethod>> results = new ArrayList<>();
        try {
            List<PsiGenerationInfo<PsiMethod>> prototypes = convert2GenerationInfos(overrideOrImplementMethod(psiClass, baseMethod, false));
            if (prototypes.isEmpty()) {
                return null;
            }

            PsiSubstitutor substitutor =
                TypeConversionUtil.getSuperClassSubstitutor(baseMethod.getContainingClass(), psiClass, PsiSubstitutor.EMPTY);
            PsiElement anchor = getDefaultAnchorToOverrideOrImplement(psiClass, baseMethod, substitutor);
            results = GenerateMembersUtil.insertMembersBeforeAnchor(psiClass, anchor, prototypes);

            return results;
        }
        finally {
            PsiFile psiFile = psiClass.getContainingFile();
            Editor editor = fileEditorManager.openTextEditor(
                OpenFileDescriptorFactory.getInstance(psiFile.getProject()).builder(psiFile.getVirtualFile()).build(),
                true
            );
            if (editor != null && !results.isEmpty()) {
                results.get(0).positionCaret(editor, true);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        }
    }

    @Nullable
    @RequiredReadAction
    public static PsiClass getContextClass(Project project, Editor editor, PsiFile file, boolean allowInterface) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        do {
            element = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        }
        while (element instanceof PsiTypeParameter);

        PsiClass aClass = (PsiClass)element;
        if (aClass instanceof PsiSyntheticClass) {
            return null;
        }
        return aClass == null || !allowInterface && aClass.isInterface() ? null : aClass;
    }

    @RequiredUIAccess
    public static void overrideOrImplementMethodsInRightPlace(
        Editor editor1,
        PsiClass aClass,
        Collection<PsiMethodMember> members,
        boolean copyJavadoc
    ) {
        boolean insert = CodeStyleSettingsManager.getSettings(aClass.getProject()).INSERT_OVERRIDE_ANNOTATION;
        overrideOrImplementMethodsInRightPlace(editor1, aClass, members, copyJavadoc, insert);
    }

    @RequiredUIAccess
    public static List<PsiMethod> overrideOrImplementMethodCandidates(
        PsiClass aClass,
        Collection<CandidateInfo> candidatesToImplement,
        boolean copyJavadoc
    ) throws IncorrectOperationException {
        boolean insert = CodeStyleSettingsManager.getSettings(aClass.getProject()).INSERT_OVERRIDE_ANNOTATION;
        return overrideOrImplementMethodCandidates(aClass, candidatesToImplement, copyJavadoc, insert);
    }
}