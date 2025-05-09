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
package com.intellij.java.impl.codeInsight.generation;

import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiField;
import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.language.psi.PsiModifier;
import com.intellij.java.language.psi.util.PropertyUtil;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.component.extension.Extensions;

/**
 * @author anna
 * @since 2013-03-04
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public abstract class GetterSetterPrototypeProvider {
    public static final ExtensionPointName<GetterSetterPrototypeProvider> EP_NAME =
        ExtensionPointName.create(GetterSetterPrototypeProvider.class);

    public abstract boolean canGeneratePrototypeFor(PsiField field);

    public abstract PsiMethod[] generateGetters(PsiField field);

    public abstract PsiMethod[] generateSetters(PsiField field);

    public PsiMethod[] findGetters(PsiClass psiClass, String propertyName) {
        return null;
    }

    public String suggestGetterName(String propertyName) {
        return null;
    }

    public boolean isSimpleGetter(PsiMethod method, String oldPropertyName) {
        return false;
    }

    public abstract boolean isReadOnly(PsiField field);

    public static PsiMethod[] generateGetterSetters(PsiField field, boolean generateGetter) {
        return generateGetterSetters(field, generateGetter, true);
    }

    public static PsiMethod[] generateGetterSetters(PsiField field, boolean generateGetter, boolean invalidTemplate) {
        for (GetterSetterPrototypeProvider provider : Extensions.getExtensions(EP_NAME)) {
            if (provider.canGeneratePrototypeFor(field)) {
                return generateGetter ? provider.generateGetters(field) : provider.generateSetters(field);
            }
        }
        return new PsiMethod[]{
            generateGetter
                ? GenerateMembersUtil.generateGetterPrototype(field, invalidTemplate)
                : GenerateMembersUtil.generateSetterPrototype(field, invalidTemplate)
        };
    }

    public static boolean isReadOnlyProperty(PsiField field) {
        for (GetterSetterPrototypeProvider provider : Extensions.getExtensions(EP_NAME)) {
            if (provider.canGeneratePrototypeFor(field)) {
                return provider.isReadOnly(field);
            }
        }
        return field.hasModifierProperty(PsiModifier.FINAL);
    }

    public static PsiMethod[] findGetters(PsiClass aClass, String propertyName, boolean isStatic) {
        if (!isStatic) {
            for (GetterSetterPrototypeProvider provider : Extensions.getExtensions(EP_NAME)) {
                PsiMethod[] getterSetter = provider.findGetters(aClass, propertyName);
                if (getterSetter != null) {
                    return getterSetter;
                }
            }
        }
        PsiMethod propertyGetterSetter = PropertyUtil.findPropertyGetter(aClass, propertyName, isStatic, false);
        if (propertyGetterSetter != null) {
            return new PsiMethod[]{propertyGetterSetter};
        }
        return null;
    }

    public static String suggestNewGetterName(String oldPropertyName, String newPropertyName, PsiMethod method) {
        for (GetterSetterPrototypeProvider provider : Extensions.getExtensions(EP_NAME)) {
            if (provider.isSimpleGetter(method, oldPropertyName)) {
                return provider.suggestGetterName(newPropertyName);
            }
        }
        return null;
    }
}
