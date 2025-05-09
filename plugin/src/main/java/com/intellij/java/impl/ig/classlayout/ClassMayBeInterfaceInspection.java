/*
 * Copyright 2003-2007 Dave Griffith, Bas Leijdekkers
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
package com.intellij.java.impl.ig.classlayout;

import com.intellij.java.indexing.search.searches.ClassInheritorsSearch;
import com.intellij.java.language.psi.*;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.localize.InspectionGadgetsLocalize;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.content.scope.SearchScope;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.psi.PsiManager;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.project.Project;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

@ExtensionImpl
public class ClassMayBeInterfaceInspection extends BaseInspection {
    @Nonnull
    @Override
    public String getDisplayName() {
        return InspectionGadgetsLocalize.classMayBeInterfaceDisplayName().get();
    }

    @Nonnull
    @Override
    protected String buildErrorString(Object... infos) {
        return InspectionGadgetsLocalize.classMayBeInterfaceProblemDescriptor().get();
    }

    @Override
    protected InspectionGadgetsFix buildFix(Object... infos) {
        return new ClassMayBeInterfaceFix();
    }

    private static class ClassMayBeInterfaceFix extends InspectionGadgetsFix {
        @Nonnull
        @Override
        public String getName() {
            return InspectionGadgetsLocalize.classMayBeInterfaceConvertQuickfix().get();
        }

        @Override
        @RequiredReadAction
        public void doFix(Project project, ProblemDescriptor descriptor) throws IncorrectOperationException {
            final PsiIdentifier classNameIdentifier = (PsiIdentifier)descriptor.getPsiElement();
            final PsiClass interfaceClass = (PsiClass)classNameIdentifier.getParent();
            moveSubClassExtendsToImplements(interfaceClass);
            changeClassToInterface(interfaceClass);
            moveImplementsToExtends(interfaceClass);
        }

        @RequiredReadAction
        private static void changeClassToInterface(PsiClass aClass)
            throws IncorrectOperationException {
            final PsiIdentifier nameIdentifier = aClass.getNameIdentifier();
            if (nameIdentifier == null) {
                return;
            }
            final PsiKeyword classKeyword = PsiTreeUtil.getPrevSiblingOfType(nameIdentifier, PsiKeyword.class);
            final PsiManager manager = aClass.getManager();
            final PsiElementFactory factory = JavaPsiFacade.getInstance(manager.getProject()).getElementFactory();
            final PsiKeyword interfaceKeyword = factory.createKeyword(PsiKeyword.INTERFACE);
            if (classKeyword == null) {
                return;
            }
            final PsiModifierList modifierList = aClass.getModifierList();
            if (modifierList != null) {
                modifierList.setModifierProperty(PsiModifier.ABSTRACT, false);
                modifierList.setModifierProperty(PsiModifier.FINAL, false);
            }
            classKeyword.replace(interfaceKeyword);
        }

        private static void moveImplementsToExtends(PsiClass anInterface)
            throws IncorrectOperationException {
            final PsiReferenceList extendsList = anInterface.getExtendsList();
            if (extendsList == null) {
                return;
            }
            final PsiReferenceList implementsList = anInterface.getImplementsList();
            if (implementsList == null) {
                return;
            }
            final PsiJavaCodeReferenceElement[] referenceElements = implementsList.getReferenceElements();
            for (final PsiJavaCodeReferenceElement referenceElement : referenceElements) {
                extendsList.add(referenceElement);
                referenceElement.delete();
            }
        }

        private static void moveSubClassExtendsToImplements(PsiClass oldClass)
            throws IncorrectOperationException {
            final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(oldClass.getProject()).getElementFactory();
            final PsiJavaCodeReferenceElement classReference = elementFactory.createClassReferenceElement(oldClass);
            final SearchScope searchScope = oldClass.getUseScope();
            for (final PsiClass inheritor : ClassInheritorsSearch.search(oldClass, searchScope, false)) {
                final PsiReferenceList extendsList = inheritor.getExtendsList();
                if (extendsList == null) {
                    continue;
                }
                final PsiReferenceList implementsList = inheritor.getImplementsList();
                moveReference(extendsList, implementsList, classReference);
            }
        }

        private static void moveReference(
            @Nonnull PsiReferenceList source,
            @Nullable PsiReferenceList target,
            @Nonnull PsiJavaCodeReferenceElement reference
        ) throws IncorrectOperationException {
            final PsiJavaCodeReferenceElement[] sourceReferences = source.getReferenceElements();
            final String fqName = reference.getQualifiedName();
            for (final PsiJavaCodeReferenceElement sourceReference : sourceReferences) {
                final String implementsReferenceFqName = sourceReference.getQualifiedName();
                if (fqName.equals(implementsReferenceFqName)) {
                    if (target != null) {
                        target.add(sourceReference);
                    }
                    sourceReference.delete();
                }
            }
        }
    }

    @Override
    public BaseInspectionVisitor buildVisitor() {
        return new ClassMayBeInterfaceVisitor();
    }

    private static class ClassMayBeInterfaceVisitor extends BaseInspectionVisitor {
        @Override
        public void visitClass(@Nonnull PsiClass aClass) {
            // no call to super, so that it doesn't drill down to inner classes
            if (aClass.isInterface() || aClass.isAnnotationType() || aClass.isEnum()
                || aClass instanceof PsiTypeParameter || aClass instanceof PsiAnonymousClass
                || !mayBeInterface(aClass)) {
                return;
            }
            registerClassError(aClass);
        }

        public static boolean mayBeInterface(PsiClass aClass) {
            final PsiReferenceList extendsList = aClass.getExtendsList();
            if (extendsList != null) {
                final PsiJavaCodeReferenceElement[] extendsElements = extendsList.getReferenceElements();
                if (extendsElements.length > 0) {
                    return false;
                }
            }
            final PsiClassInitializer[] initializers = aClass.getInitializers();
            return initializers.length <= 0
                && allMethodsPublicAbstract(aClass)
                && allFieldsPublicStaticFinal(aClass)
                && allInnerClassesPublic(aClass);
        }

        private static boolean allFieldsPublicStaticFinal(PsiClass aClass) {
            boolean allFieldsStaticFinal = true;
            final PsiField[] fields = aClass.getFields();
            for (final PsiField field : fields) {
                if (!(field.isPublic() && field.isStatic() && field.isFinal())) {
                    allFieldsStaticFinal = false;
                }
            }
            return allFieldsStaticFinal;
        }

        private static boolean allMethodsPublicAbstract(PsiClass aClass) {
            final PsiMethod[] methods = aClass.getMethods();
            for (final PsiMethod method : methods) {
                if (!(method.isPublic() && method.isAbstract())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean allInnerClassesPublic(PsiClass aClass) {
            final PsiClass[] innerClasses = aClass.getInnerClasses();
            for (PsiClass innerClass : innerClasses) {
                if (!innerClass.isPublic()) {
                    return false;
                }
            }
            return true;
        }
    }
}
