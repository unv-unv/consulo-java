// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.language.annoPackages;

import com.intellij.java.language.codeInsight.Nullability;
import com.intellij.java.language.codeInsight.NullabilityAnnotationInfo;
import com.intellij.java.language.psi.PsiAnnotation;
import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.language.psi.PsiElement;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Support for custom annotation packages
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface AnnotationPackageSupport {
    ExtensionPointName<AnnotationPackageSupport> EP_NAME = ExtensionPointName.create(AnnotationPackageSupport.class);

    /**
     * Returns nullability by a container annotation
     *
     * @param anno         annotation to check
     * @param context      target PsiElement (usually, method or variable)
     * @param types        target types
     * @param superPackage if true then the annotation is applied to the super-package
     * @return NullabilityAnnotationInfo object if given annotation is recognized default annotation; null otherwise
     */
    @Nullable
    @RequiredReadAction
    default NullabilityAnnotationInfo getNullabilityByContainerAnnotation(
        @Nonnull PsiAnnotation anno,
        @Nonnull PsiElement context,
        @Nonnull PsiAnnotation.TargetType[] types,
        boolean superPackage
    ) {
        return null;
    }

    /**
     * @param nullability desired nullability
     * @return list of explicit annotations which denote given nullability (and may denote additional semantics)
     */
    @Nonnull
    default List<String> getNullabilityAnnotations(@Nonnull Nullability nullability) {
        return Collections.emptyList();
    }

    /**
     * @return true if the annotations defined by this support cannot be placed at wildcards or type parameters
     */
    default boolean isTypeUseAnnotationLocationRestricted() {
        return false;
    }

    /**
     * @return true if the annotations defined by this support can be used to annotate local variables
     */
    default boolean canAnnotateLocals() {
        return true;
    }
}
