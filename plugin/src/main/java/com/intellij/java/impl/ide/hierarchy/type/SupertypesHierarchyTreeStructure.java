/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.java.impl.ide.hierarchy.type;

import com.intellij.java.language.psi.CommonClassNames;
import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiFunctionalExpression;
import com.intellij.java.language.psi.util.PsiUtil;
import consulo.ide.impl.idea.ide.hierarchy.HierarchyNodeDescriptor;
import consulo.ide.impl.idea.ide.hierarchy.HierarchyTreeStructure;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

public final class SupertypesHierarchyTreeStructure extends HierarchyTreeStructure {
    public SupertypesHierarchyTreeStructure(Project project, PsiClass aClass) {
        super(project, new TypeHierarchyNodeDescriptor(project, null, aClass, true));
    }

    @Override
    @Nonnull
    protected final Object[] buildChildren(@Nonnull HierarchyNodeDescriptor descriptor) {
        Object element = ((TypeHierarchyNodeDescriptor)descriptor).getPsiClass();
        if (element instanceof PsiClass psiClass) {
            PsiClass[] supers = psiClass.getSupers();
            List<HierarchyNodeDescriptor> descriptors = new ArrayList<>();
            PsiClass objectClass =
                JavaPsiFacade.getInstance(myProject).findClass(CommonClassNames.JAVA_LANG_OBJECT, psiClass.getResolveScope());
            for (PsiClass aSuper : supers) {
                if (!psiClass.isInterface() || !aSuper.equals(objectClass)) {
                    descriptors.add(new TypeHierarchyNodeDescriptor(myProject, descriptor, aSuper, false));
                }
            }
            return descriptors.toArray(new HierarchyNodeDescriptor[descriptors.size()]);
        }
        else if (element instanceof PsiFunctionalExpression funcExpr) {
            PsiClass functionalInterfaceClass = PsiUtil.resolveClassInType(funcExpr.getFunctionalInterfaceType());
            if (functionalInterfaceClass != null) {
                return new HierarchyNodeDescriptor[]{new TypeHierarchyNodeDescriptor(
                    myProject,
                    descriptor,
                    functionalInterfaceClass,
                    false
                )};
            }
        }
        return ArrayUtil.EMPTY_OBJECT_ARRAY;
    }
}
