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
package com.intellij.java.impl.codeInsight.generation;

import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.impl.codeInsight.generation.OverrideImplementExploreUtil;
import com.intellij.java.language.impl.codeInsight.generation.PsiElementClassMember;
import com.intellij.java.language.impl.psi.scope.processor.VariablesProcessor;
import com.intellij.java.language.impl.psi.scope.util.PsiScopesUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.javadoc.PsiDocComment;
import com.intellij.java.language.psi.util.MethodSignature;
import com.intellij.java.language.psi.util.PropertyUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.codeEditor.ScrollType;
import consulo.document.FileDocumentManager;
import consulo.ide.impl.idea.codeInsight.CodeInsightUtilBase;
import consulo.ide.impl.idea.ide.util.MemberChooser;
import consulo.language.Language;
import consulo.language.codeStyle.CodeStyleManager;
import consulo.language.editor.generation.ClassMember;
import consulo.language.editor.generation.DelegateMethodHandler;
import consulo.language.editor.localize.CodeInsightLocalize;
import consulo.language.psi.PsiDocumentManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.resolve.ResolveState;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.annotations.NonNls;

import java.util.*;

/**
 * @author mike
 */
@ExtensionImpl
public class GenerateDelegateHandler implements DelegateMethodHandler {
  private static final Logger LOG = Logger.getInstance(GenerateDelegateHandler.class);
  private boolean myToCopyJavaDoc = false;

  @Override
  @RequiredReadAction
  public boolean isValidFor(Editor editor, PsiFile file) {
    return file instanceof PsiJavaFile
      && OverrideImplementUtil.getContextClass(editor.getProject(), editor, file, false) != null
      && isApplicable(file, editor);
  }

  @Override
  @RequiredUIAccess
  public void invoke(@Nonnull final Project project, @Nonnull final Editor editor, @Nonnull final PsiFile file) {
    if (!CodeInsightUtilBase.prepareEditorForWrite(editor)) {
      return;
    }
    if (!FileDocumentManager.getInstance().requestWriting(editor.getDocument(), project)) {
      return;
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments();

    final PsiElementClassMember target = chooseTarget(file, editor, project);
    if (target == null) {
      return;
    }

    final PsiMethodMember[] candidates = chooseMethods(target, file, editor, project);
    if (candidates == null || candidates.length == 0) {
      return;
    }

    project.getApplication().runWriteAction(() -> {
      try {
        int offset = editor.getCaretModel().getOffset();

        List<PsiGenerationInfo<PsiMethod>> prototypes = new ArrayList<>(candidates.length);
        for (PsiMethodMember candidate : candidates) {
          prototypes.add(generateDelegatePrototype(candidate, target.getElement()));
        }

        List<PsiGenerationInfo<PsiMethod>> results = GenerateMembersUtil.insertMembersAtOffset(file, offset, prototypes);

        if (!results.isEmpty()) {
          PsiMethod firstMethod = results.get(0).getPsiMember();
          final PsiCodeBlock block = firstMethod.getBody();
          assert block != null;
          final PsiElement first = block.getFirstBodyElement();
          assert first != null;
          editor.getCaretModel().moveToOffset(first.getTextRange().getStartOffset());
          editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
          editor.getSelectionModel().removeSelection();
        }
      } catch (IncorrectOperationException e) {
        LOG.error(e);
      }
    });
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  private PsiGenerationInfo<PsiMethod> generateDelegatePrototype(PsiMethodMember methodCandidate, PsiElement target) throws IncorrectOperationException {
    PsiMethod method = GenerateMembersUtil.substituteGenericMethod(methodCandidate.getElement(), methodCandidate.getSubstitutor(), target);
    clearMethod(method);

    clearModifiers(method);

    @NonNls StringBuilder call = new StringBuilder();

    PsiModifierList modifierList = null;

    if (!PsiType.VOID.equals(method.getReturnType())) {
      call.append("return ");
    }

    boolean isMethodStatic = methodCandidate.getElement().hasModifierProperty(PsiModifier.STATIC);
    if (target instanceof PsiField field) {
      modifierList = field.getModifierList();
      if (isMethodStatic) {
        call.append(methodCandidate.getContainingClass().getQualifiedName());
      } else {
        final String name = field.getName();

        final PsiParameter[] parameters = method.getParameterList().getParameters();
        for (PsiParameter parameter : parameters) {
          if (name.equals(parameter.getName())) {
            call.append("this.");
            break;
          }
        }

        call.append(name);
      }
      call.append(".");
    } else if (target instanceof PsiMethod m) {
      modifierList = m.getModifierList();
      if (isMethodStatic) {
        call.append(methodCandidate.getContainingClass().getQualifiedName()).append(".");
      } else {
        call.append(m.getName());
        call.append("().");
      }
    }

    call.append(method.getName());
    call.append("(");
    final PsiParameter[] parameters = method.getParameterList().getParameters();
    for (int j = 0; j < parameters.length; j++) {
      PsiParameter parameter = parameters[j];
      if (j > 0) {
        call.append(",");
      }
      call.append(parameter.getName());
    }
    call.append(");");

    final PsiManager psiManager = method.getManager();
    PsiStatement stmt = JavaPsiFacade.getInstance(psiManager.getProject()).getElementFactory().createStatementFromText(call.toString(), method);
    stmt = (PsiStatement) CodeStyleManager.getInstance(psiManager.getProject()).reformat(stmt);
    method.getBody().add(stmt);

    for (PsiAnnotation annotation : methodCandidate.getElement().getModifierList().getAnnotations()) {
      if (SuppressWarnings.class.getName().equals(annotation.getQualifiedName())) {
        continue;
      }
      method.getModifierList().add(annotation.copy());
    }

    if (isMethodStatic || modifierList != null && modifierList.hasModifierProperty(PsiModifier.STATIC)) {
      PsiUtil.setModifierProperty(method, PsiModifier.STATIC, true);
    }

    PsiUtil.setModifierProperty(method, PsiModifier.PUBLIC, true);

    final PsiClass targetClass = ((PsiMember) target).getContainingClass();
    LOG.assertTrue(targetClass != null);
    PsiMethod overridden = targetClass.findMethodBySignature(method, true);
    if (overridden != null && overridden.getContainingClass() != targetClass) {
      OverrideImplementUtil.annotateOnOverrideImplement(method, targetClass, overridden);
    }

    return new PsiGenerationInfo<>(method);
  }

  private void clearMethod(PsiMethod method) throws IncorrectOperationException {
    LOG.assertTrue(!method.isPhysical());
    PsiCodeBlock codeBlock = JavaPsiFacade.getInstance(method.getProject()).getElementFactory().createCodeBlock();
    if (method.getBody() != null) {
      method.getBody().replace(codeBlock);
    } else {
      method.add(codeBlock);
    }

    if (!myToCopyJavaDoc) {
      final PsiDocComment docComment = method.getDocComment();
      if (docComment != null) {
        docComment.delete();
      }
    }
  }

  @RequiredReadAction
  private static void clearModifiers(PsiMethod method) throws IncorrectOperationException {
    final PsiElement[] children = method.getModifierList().getChildren();
    for (PsiElement child : children) {
      if (child instanceof PsiKeyword) {
        child.delete();
      }
    }
  }

  @Nullable
  @RequiredReadAction
  private PsiMethodMember[] chooseMethods(PsiElementClassMember targetMember, PsiFile file, Editor editor, Project project) {
    PsiClassType.ClassResolveResult resolveResult = null;
    final PsiDocCommentOwner target = targetMember.getElement();
    if (target instanceof PsiField field) {
      resolveResult = PsiUtil.resolveGenericsClassInType(targetMember.getSubstitutor().substitute(field.getType()));
    } else if (target instanceof PsiMethod method) {
      resolveResult = PsiUtil.resolveGenericsClassInType(targetMember.getSubstitutor().substitute(method.getReturnType()));
    }

    if (resolveResult == null || resolveResult.getElement() == null) {
      return null;
    }
    PsiClass targetClass = resolveResult.getElement();
    PsiSubstitutor substitutor = resolveResult.getSubstitutor();

    int offset = editor.getCaretModel().getOffset();
    PsiElement element = file.findElementAt(offset);
    if (element == null) {
      return null;
    }
    PsiClass aClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
    if (aClass == null) {
      return null;
    }

    List<PsiMethodMember> methodInstances = new ArrayList<>();

    final PsiMethod[] allMethods;
    if (targetClass instanceof PsiTypeParameter) {
      LinkedHashSet<PsiMethod> meths = new LinkedHashSet<>();
      for (PsiClass superClass : targetClass.getSupers()) {
        meths.addAll(Arrays.asList(superClass.getAllMethods()));
      }
      allMethods = meths.toArray(new PsiMethod[meths.size()]);
    } else {
      allMethods = targetClass.getAllMethods();
    }
    final Set<MethodSignature> signatures = new HashSet<>();
    final Set<MethodSignature> existingSignatures = new HashSet<>(aClass.getVisibleSignatures());
    final Set<PsiMethodMember> selection = new HashSet<>();
    Map<PsiClass, PsiSubstitutor> superSubstitutors = new HashMap<>();

    final PsiClass containingClass = targetMember.getContainingClass();
    JavaPsiFacade facade = JavaPsiFacade.getInstance(target.getProject());
    for (PsiMethod method : allMethods) {
      final PsiClass superClass = method.getContainingClass();
      if (CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
        continue;
      }
      if (method.isConstructor()) {
        continue;
      }

      //do not suggest to override final method
      if (method.hasModifierProperty(PsiModifier.FINAL)) {
        PsiMethod overridden = containingClass.findMethodBySignature(method, true);
        if (overridden != null && overridden.getContainingClass() != containingClass) {
          continue;
        }
      }

      PsiSubstitutor superSubstitutor = superSubstitutors.get(superClass);
      if (superSubstitutor == null) {
        superSubstitutor = TypeConversionUtil.getSuperClassSubstitutor(superClass, targetClass, substitutor);
        superSubstitutors.put(superClass, superSubstitutor);
      }
      PsiSubstitutor methodSubstitutor = OverrideImplementExploreUtil.correctSubstitutor(method, superSubstitutor);
      MethodSignature signature = method.getSignature(methodSubstitutor);
      if (!signatures.contains(signature)) {
        signatures.add(signature);
        if (facade.getResolveHelper().isAccessible(method, target, aClass)) {
          final PsiMethodMember methodMember = new PsiMethodMember(method, methodSubstitutor);
          methodInstances.add(methodMember);
          if (!existingSignatures.contains(signature)) {
            selection.add(methodMember);
          }
        }
      }
    }

    PsiMethodMember[] result;
    if (!project.getApplication().isUnitTestMode()) {
      MemberChooser<PsiElementClassMember> chooser = new MemberChooser<>(
        methodInstances.toArray(new PsiMethodMember[methodInstances.size()]),
        false,
        true,
        project
      );
      chooser.setTitle(CodeInsightLocalize.generateDelegateMethodChooserTitle());
      chooser.setCopyJavadocVisible(true);
      if (!selection.isEmpty()) {
        chooser.selectElements(selection.toArray(new ClassMember[selection.size()]));
      }
      chooser.show();

      if (chooser.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
        return null;
      }

      myToCopyJavaDoc = chooser.isCopyJavadoc();
      final List<PsiElementClassMember> list = chooser.getSelectedElements();
      result = list.toArray(new PsiMethodMember[list.size()]);
    } else {
      result = methodInstances.isEmpty() ? new PsiMethodMember[0] : new PsiMethodMember[]{methodInstances.get(0)};
    }

    return result;
  }

  public void setToCopyJavaDoc(boolean toCopyJavaDoc) {
    myToCopyJavaDoc = toCopyJavaDoc;
  }

  public static boolean isApplicable(PsiFile file, Editor editor) {
    ClassMember[] targetElements = getTargetElements(file, editor);
    return targetElements != null && targetElements.length > 0;
  }

  @Nullable
  private static PsiElementClassMember chooseTarget(PsiFile file, Editor editor, Project project) {
    final PsiElementClassMember[] targetElements = getTargetElements(file, editor);
    if (targetElements == null || targetElements.length == 0) {
      return null;
    }
    if (!project.getApplication().isUnitTestMode()) {
      MemberChooser<PsiElementClassMember> chooser = new MemberChooser<>(targetElements, false, false, project);
      chooser.setTitle(CodeInsightLocalize.generateDelegateTargetChooserTitle());
      chooser.setCopyJavadocVisible(false);
      chooser.show();

      if (chooser.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
        return null;
      }

      final List<PsiElementClassMember> selectedElements = chooser.getSelectedElements();

      if (selectedElements != null && selectedElements.size() > 0) {
        return selectedElements.get(0);
      }
    } else {
      return targetElements[0];
    }
    return null;
  }

  @Nullable
  private static PsiElementClassMember[] getTargetElements(PsiFile file, Editor editor) {
    int offset = editor.getCaretModel().getOffset();
    PsiElement element = file.findElementAt(offset);
    if (element == null) {
      return null;
    }
    PsiClass aClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
    if (aClass == null) {
      return null;
    }

    List<PsiElementClassMember> result = new ArrayList<>();

    while (aClass != null) {
      collectTargetsInClass(element, aClass, result);
      if (aClass.hasModifierProperty(PsiModifier.STATIC)) {
        break;
      }
      aClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class, true);
    }

    return result.toArray(new PsiElementClassMember[result.size()]);
  }

  private static void collectTargetsInClass(PsiElement element, final PsiClass aClass, List<PsiElementClassMember> result) {
    final PsiField[] fields = aClass.getAllFields();
    PsiResolveHelper helper = JavaPsiFacade.getInstance(aClass.getProject()).getResolveHelper();
    for (PsiField field : fields) {
      final PsiType type = field.getType();
      if (helper.isAccessible(field, aClass, aClass) && type instanceof PsiClassType && !PsiTreeUtil.isAncestor(field, element, false)) {
        final PsiClass containingClass = field.getContainingClass();
        if (containingClass != null) {
          result.add(new PsiFieldMember(field, TypeConversionUtil.getSuperClassSubstitutor(containingClass, aClass, PsiSubstitutor.EMPTY)));
        }
      }
    }

    final PsiMethod[] methods = aClass.getAllMethods();
    for (PsiMethod method : methods) {
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass == null || CommonClassNames.JAVA_LANG_OBJECT.equals(containingClass.getQualifiedName())) {
        continue;
      }
      final PsiType returnType = method.getReturnType();
      if (returnType != null && PropertyUtil.isSimplePropertyGetter(method) && helper.isAccessible(method, aClass, aClass) &&
          returnType instanceof PsiClassType && !PsiTreeUtil.isAncestor(method, element, false)) {
        result.add(new PsiMethodMember(method, TypeConversionUtil.getSuperClassSubstitutor(containingClass, aClass, PsiSubstitutor.EMPTY)));
      }
    }

    if (aClass instanceof PsiAnonymousClass) {
      VariablesProcessor proc = new VariablesProcessor(false) {
        @Override
        protected boolean check(PsiVariable var, ResolveState state) {
          return var.hasModifierProperty(PsiModifier.FINAL) && var instanceof PsiLocalVariable || var instanceof PsiParameter;
        }
      };
      PsiElement scope = aClass;
      while (scope != null) {
        if (scope instanceof PsiFile || scope instanceof PsiMethod || scope instanceof PsiClassInitializer) {
          break;
        }
        scope = scope.getParent();
      }
      if (scope != null) {
        PsiScopesUtil.treeWalkUp(proc, aClass, scope);

        for (int i = 0; i < proc.size(); i++) {
          final PsiVariable psiVariable = proc.getResult(i);
          final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(aClass.getProject());
          final PsiType type = psiVariable.getType();
          result.add(new PsiFieldMember(elementFactory.createField(
            psiVariable.getName(),
            type instanceof PsiEllipsisType ellipsisType ? ellipsisType.toArrayType() : type)
          ) {
            @Override
            public PsiClass getContainingClass() {
              return aClass;
            }
          });
        }
      }
    }
  }

  @Nonnull
  @Override
  public Language getLanguage() {
    return JavaLanguage.INSTANCE;
  }
}
