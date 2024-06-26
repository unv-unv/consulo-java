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
package com.intellij.java.impl.codeInspection.actions;

import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.MethodSignatureUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.codeEditor.Editor;
import consulo.language.editor.FileModificationService;
import consulo.language.editor.TargetElementUtil;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.editor.intention.IntentionMetaData;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiReference;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.project.Project;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.*;

@ExtensionImpl
@IntentionMetaData(ignoreId = "java.UnimplementInterfaceAction", categories = {"Java", "Declaration"}, fileExtensions = "java")
public class UnimplementInterfaceAction implements IntentionAction {
  private String myName = "Interface";

  @Override
  @Nonnull
  public String getText() {
    return "Unimplement " + myName;
  }

  @Override
  public boolean isAvailable(@Nonnull Project project, Editor editor, PsiFile file) {
    if (!(file instanceof PsiJavaFile)) return false;
    final PsiReference psiReference = TargetElementUtil.findReference(editor);
    if (psiReference == null) return false;

    final PsiReferenceList referenceList = PsiTreeUtil.getParentOfType(psiReference.getElement(), PsiReferenceList.class);
    if (referenceList == null) return false;

    final PsiClass psiClass = PsiTreeUtil.getParentOfType(referenceList, PsiClass.class);
    if (psiClass == null) return false;

    if (psiClass.getExtendsList() != referenceList && psiClass.getImplementsList() != referenceList) return false;

    PsiJavaCodeReferenceElement referenceElement = getTopLevelRef(psiReference, referenceList);
    if (referenceElement == null) return false;

    final PsiElement target = referenceElement.resolve();
    if (target == null || !(target instanceof PsiClass)) return false;

    PsiClass targetClass = (PsiClass)target;
    if (targetClass.isInterface()) {
      myName = "Interface";
    }
    else {
      myName = "Class";
    }

    return true;
  }

  @Nullable
  private static PsiJavaCodeReferenceElement getTopLevelRef(PsiReference psiReference, PsiReferenceList referenceList) {
    PsiElement element = psiReference.getElement();
    while (element.getParent() != referenceList) {
      element = element.getParent();
      if (element == null) return null;
    }

    if (!(element instanceof PsiJavaCodeReferenceElement)) return null;
    return (PsiJavaCodeReferenceElement)element;
  }

  @Override
  public void invoke(@Nonnull final Project project, final Editor editor, final PsiFile file) throws IncorrectOperationException {
    if (!FileModificationService.getInstance().preparePsiElementForWrite(file)) return;

    final PsiReference psiReference = TargetElementUtil.findReference(editor);
    if (psiReference == null) return;

    final PsiReferenceList referenceList = PsiTreeUtil.getParentOfType(psiReference.getElement(), PsiReferenceList.class);
    if (referenceList == null) return;

    final PsiClass psiClass = PsiTreeUtil.getParentOfType(referenceList, PsiClass.class);
    if (psiClass == null) return;

    if (psiClass.getExtendsList() != referenceList && psiClass.getImplementsList() != referenceList) return;

    PsiJavaCodeReferenceElement element = getTopLevelRef(psiReference, referenceList);
    if (element == null) return;

    final PsiElement target = element.resolve();
    if (target == null || !(target instanceof PsiClass)) return;

    PsiClass targetClass = (PsiClass)target;

    final Map<PsiMethod, PsiMethod> implementations = new HashMap<PsiMethod, PsiMethod>();
    for (PsiMethod psiMethod : targetClass.getAllMethods()) {
      final PsiMethod implementingMethod = MethodSignatureUtil.findMethodBySuperMethod(psiClass, psiMethod, false);
      if (implementingMethod != null) {
        implementations.put(psiMethod, implementingMethod);
      }
    }
    element.delete();

    final Set<PsiMethod> superMethods = new HashSet<PsiMethod>();
    for (PsiClass aClass : psiClass.getSupers()) {
      Collections.addAll(superMethods, aClass.getAllMethods());
    }
    final PsiMethod[] psiMethods = targetClass.getAllMethods();
    for (PsiMethod psiMethod : psiMethods) {
      if (superMethods.contains(psiMethod)) continue;
      final PsiMethod impl = implementations.get(psiMethod);
      if (impl != null) impl.delete();
    }
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }
}
