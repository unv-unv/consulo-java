// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.language.impl.psi.impl.source;

import com.intellij.java.language.codeInsight.AnnotationTargetUtil;
import com.intellij.java.language.impl.psi.impl.PsiImplUtil;
import com.intellij.java.language.impl.psi.impl.cache.ModifierFlags;
import com.intellij.java.language.impl.psi.impl.java.stubs.JavaStubElementTypes;
import com.intellij.java.language.impl.psi.impl.java.stubs.PsiModifierListStub;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.augment.PsiAugmentProvider;
import consulo.annotation.access.RequiredReadAction;
import consulo.language.ast.ASTNode;
import consulo.language.ast.IElementType;
import consulo.language.impl.ast.CompositeElement;
import consulo.language.impl.ast.Factory;
import consulo.language.impl.ast.TreeElement;
import consulo.language.impl.psi.CheckUtil;
import consulo.language.impl.psi.CodeEditUtil;
import consulo.language.impl.psi.SourceTreeToPsiMap;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.psi.PsiFile;
import consulo.language.util.IncorrectOperationException;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.interner.Interner;
import consulo.util.lang.BitUtil;
import jakarta.annotation.Nonnull;

import java.util.*;

import static com.intellij.java.language.psi.PsiModifier.*;

public class PsiModifierListImpl extends JavaStubPsiElement<PsiModifierListStub> implements PsiModifierList {
    private static final Map<String, IElementType> NAME_TO_KEYWORD_TYPE_MAP;
    private static final Map<IElementType, String> KEYWORD_TYPE_TO_NAME_MAP;

    static {
        NAME_TO_KEYWORD_TYPE_MAP = new HashMap<>();
        NAME_TO_KEYWORD_TYPE_MAP.put(PUBLIC, JavaTokenType.PUBLIC_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(PROTECTED, JavaTokenType.PROTECTED_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(PRIVATE, JavaTokenType.PRIVATE_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(STATIC, JavaTokenType.STATIC_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(ABSTRACT, JavaTokenType.ABSTRACT_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(FINAL, JavaTokenType.FINAL_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(NATIVE, JavaTokenType.NATIVE_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(SYNCHRONIZED, JavaTokenType.SYNCHRONIZED_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(STRICTFP, JavaTokenType.STRICTFP_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(TRANSIENT, JavaTokenType.TRANSIENT_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(VOLATILE, JavaTokenType.VOLATILE_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(DEFAULT, JavaTokenType.DEFAULT_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(OPEN, JavaTokenType.OPEN_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(TRANSITIVE, JavaTokenType.TRANSITIVE_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(SEALED, JavaTokenType.SEALED_KEYWORD);
        NAME_TO_KEYWORD_TYPE_MAP.put(NON_SEALED, JavaTokenType.NON_SEALED_KEYWORD);

        KEYWORD_TYPE_TO_NAME_MAP = new HashMap<>();
        for (String name : NAME_TO_KEYWORD_TYPE_MAP.keySet()) {
            KEYWORD_TYPE_TO_NAME_MAP.put(NAME_TO_KEYWORD_TYPE_MAP.get(name), name);
        }
    }

    private volatile ModifierCache myModifierCache;

    public PsiModifierListImpl(PsiModifierListStub stub) {
        super(stub, JavaStubElementTypes.MODIFIER_LIST);
    }

    public PsiModifierListImpl(ASTNode node) {
        super(node);
    }

    @Override
    @RequiredReadAction
    public boolean hasModifierProperty(@Nonnull String name) {
        ModifierCache modifierCache = myModifierCache;
        if (modifierCache == null || !modifierCache.isUpToDate()) {
            myModifierCache = modifierCache = calcModifiers();
        }
        return modifierCache.modifiers.contains(name);
    }

    @RequiredReadAction
    private ModifierCache calcModifiers() {
        Set<String> modifiers = calcExplicitModifiers();
        modifiers.addAll(calcImplicitModifiers(modifiers));
        if (!modifiers.contains(PUBLIC) && !modifiers.contains(PROTECTED) && !modifiers.contains(PRIVATE)) {
            modifiers.add(PACKAGE_LOCAL);
        }
        PsiFile file = getContainingFile();
        return new ModifierCache(file, PsiAugmentProvider.transformModifierProperties(this, file.getProject(), modifiers));
    }

    @RequiredReadAction
    private Set<String> calcExplicitModifiers() {
        Set<String> explicitModifiers = new HashSet<>();
        PsiModifierListStub stub = getGreenStub();
        if (stub != null) {
            int mask = stub.getModifiersMask();
            for (int i = 0; i < 31; i++) {
                int flag = 1 << i;
                if (BitUtil.isSet(mask, flag)) {
                    ContainerUtil.addIfNotNull(explicitModifiers, ModifierFlags.MODIFIER_FLAG_TO_NAME_MAP.get(flag));
                }
            }
        }
        else {
            for (ASTNode child : getNode().getChildren(null)) {
                ContainerUtil.addIfNotNull(explicitModifiers, KEYWORD_TYPE_TO_NAME_MAP.get(child.getElementType()));
            }
        }

        return explicitModifiers;
    }

    private Set<String> calcImplicitModifiers(Set<String> explicitModifiers) {
        Set<String> implicitModifiers = new HashSet<>();
        PsiElement parent = getParent();
        if (parent instanceof PsiClass psiClass) {
            PsiElement grandParent = psiClass.getContext();
            if (grandParent instanceof PsiClass outerClass && outerClass.isInterface()) {
                Collections.addAll(implicitModifiers, PUBLIC, STATIC);
            }
            if (psiClass.isInterface()) {
                implicitModifiers.add(ABSTRACT);

                // nested or local interface is implicitly static
                if (!(grandParent instanceof PsiFile)) {
                    implicitModifiers.add(STATIC);
                }
            }
            if (psiClass.isRecord()) {
                if (!(grandParent instanceof PsiFile)) {
                    implicitModifiers.add(STATIC);
                }
                implicitModifiers.add(FINAL);
            }
            if (psiClass.isEnum()) {
                if (!(grandParent instanceof PsiFile)) {
                    implicitModifiers.add(STATIC);
                }
                List<PsiField> fields = parent instanceof PsiExtensibleClass extensibleClass
                    ? extensibleClass.getOwnFields()
                    : Arrays.asList(psiClass.getFields());
                boolean hasSubClass =
                    ContainerUtil.find(fields, field -> field instanceof PsiEnumConstant eс && eс.getInitializingClass() != null) != null;
                if (hasSubClass) {
                    implicitModifiers.add(SEALED);
                }
                else {
                    implicitModifiers.add(FINAL);
                }

                List<PsiMethod> methods = parent instanceof PsiExtensibleClass extensibleClass
                    ? extensibleClass.getOwnMethods()
                    : Arrays.asList(psiClass.getMethods());
                for (PsiMethod method : methods) {
                    if (method.isAbstract()) {
                        implicitModifiers.add(ABSTRACT);
                        break;
                    }
                }
            }
        }
        else if (parent instanceof PsiMethod method) {
            PsiClass aClass = method.getContainingClass();
            if (aClass != null && aClass.isInterface()) {
                if (!explicitModifiers.contains(PRIVATE)) {
                    implicitModifiers.add(PUBLIC);
                    if (!explicitModifiers.contains(DEFAULT) && !explicitModifiers.contains(STATIC)) {
                        implicitModifiers.add(ABSTRACT);
                    }
                }
            }
            else if (aClass != null && aClass.isEnum() && method.isConstructor()) {
                implicitModifiers.add(PRIVATE);
            }
        }
        else if (parent instanceof PsiRecordComponent) {
            implicitModifiers.add(FINAL);
        }
        else if (parent instanceof PsiField field) {
            if (parent instanceof PsiEnumConstant) {
                Collections.addAll(implicitModifiers, PUBLIC, STATIC, FINAL);
            }
            else {
                PsiClass aClass = field.getContainingClass();
                if (aClass != null && aClass.isInterface()) {
                    Collections.addAll(implicitModifiers, PUBLIC, STATIC, FINAL);
                }
            }
        }
        else if (parent instanceof PsiParameter parameter
            && parameter.getParent() instanceof PsiCatchSection
            && parameter.getType() instanceof PsiDisjunctionType) {
            Collections.addAll(implicitModifiers, FINAL);
        }
        else if (parent instanceof PsiResourceVariable) {
            Collections.addAll(implicitModifiers, FINAL);
        }
        return implicitModifiers;
    }

    @Override
    @RequiredReadAction
    public boolean hasExplicitModifier(@Nonnull String name) {
        PsiModifierListStub stub = getGreenStub();
        if (stub != null) {
            return BitUtil.isSet(stub.getModifiersMask(), ModifierFlags.NAME_TO_MODIFIER_FLAG_MAP.getInt(name));
        }

        CompositeElement tree = (CompositeElement)getNode();
        IElementType type = NAME_TO_KEYWORD_TYPE_MAP.get(name);
        return type != null && tree.findChildByType(type) != null;
    }

    @Override
    @RequiredReadAction
    public void setModifierProperty(@Nonnull String name, boolean value) throws IncorrectOperationException {
        checkSetModifierProperty(name, value);

        PsiElement parent = getParent();
        PsiElement grandParent = parent != null ? parent.getParent() : null;
        IElementType type = NAME_TO_KEYWORD_TYPE_MAP.get(name);
        CompositeElement treeElement = (CompositeElement)getNode();

        // There is a possible case that parameters list occupies more than one line and its elements are aligned. Modifiers list change
        // changes horizontal position of parameters list start, hence, we need to reformat them in order to preserve alignment.
        if (parent instanceof PsiMethod method) {
            ASTNode node = method.getParameterList().getNode();
            if (node != null) { // could be a compact constructor parameter list
                CodeEditUtil.markToReformat(node, true);
            }
        }

        if (value) {
            if (type == JavaTokenType.PUBLIC_KEYWORD ||
                type == JavaTokenType.PRIVATE_KEYWORD ||
                type == JavaTokenType.PROTECTED_KEYWORD ||
                type == null /* package-private */) {
                if (type != JavaTokenType.PUBLIC_KEYWORD) {
                    setModifierProperty(PUBLIC, false);
                }
                if (type != JavaTokenType.PRIVATE_KEYWORD) {
                    setModifierProperty(PRIVATE, false);
                }
                if (type != JavaTokenType.PROTECTED_KEYWORD) {
                    setModifierProperty(PROTECTED, false);
                }
                if (type == null) {
                    return;
                }
            }

            if (type == JavaTokenType.SEALED_KEYWORD || type == JavaTokenType.FINAL_KEYWORD || type == JavaTokenType.NON_SEALED_KEYWORD) {
                if (type != JavaTokenType.SEALED_KEYWORD) {
                    setModifierProperty(SEALED, false);
                }
                if (type != JavaTokenType.NON_SEALED_KEYWORD) {
                    setModifierProperty(NON_SEALED, false);
                }
                if (type != JavaTokenType.FINAL_KEYWORD) {
                    setModifierProperty(FINAL, false);
                }
            }

            if (parent instanceof PsiField && grandParent instanceof PsiClass psiClass && psiClass.isInterface()) {
                if (type == JavaTokenType.PUBLIC_KEYWORD || type == JavaTokenType.STATIC_KEYWORD || type == JavaTokenType.FINAL_KEYWORD) {
                    return;
                }
            }
            else if (parent instanceof PsiMethod && grandParent instanceof PsiClass psiClass && psiClass.isInterface()) {
                if (type == JavaTokenType.PUBLIC_KEYWORD || type == JavaTokenType.ABSTRACT_KEYWORD) {
                    return;
                }
            }
            else if (parent instanceof PsiClass && grandParent instanceof PsiClass outerClass && outerClass.isInterface()) {
                if (type == JavaTokenType.PUBLIC_KEYWORD) {
                    return;
                }
            }
            else if (parent instanceof PsiAnnotationMethod && grandParent instanceof PsiClass psiClass && psiClass.isAnnotationType()) {
                if (type == JavaTokenType.PUBLIC_KEYWORD || type == JavaTokenType.ABSTRACT_KEYWORD) {
                    return;
                }
            }

            if (treeElement.findChildByType(type) == null) {
                TreeElement keyword = Factory.createSingleLeafElement(type, name, null, getManager());
                treeElement.addInternal(keyword, keyword, null, null);
            }
        }
        else {
            if (type == null /* package-private */) {
                throw new IncorrectOperationException("Cannot reset package-private modifier."); //?
            }

            ASTNode child = treeElement.findChildByType(type);
            if (child != null) {
                SourceTreeToPsiMap.treeToPsiNotNull(child).delete();
            }
        }
    }

    @Override
    public void checkSetModifierProperty(@Nonnull String name, boolean value) throws IncorrectOperationException {
        CheckUtil.checkWritable(this);
    }

    @Override
    @Nonnull
    public PsiAnnotation[] getAnnotations() {
        PsiAnnotation[] own = getStubOrPsiChildren(JavaStubElementTypes.ANNOTATION, PsiAnnotation.ARRAY_FACTORY);
        List<PsiAnnotation> ext = PsiAugmentProvider.collectAugments(this, PsiAnnotation.class, null);
        return ArrayUtil.mergeArrayAndCollection(own, ext, PsiAnnotation.ARRAY_FACTORY);
    }

    @Nonnull
    @Override
    @RequiredReadAction
    public PsiAnnotation[] getApplicableAnnotations() {
        PsiAnnotation.TargetType[] targets = AnnotationTargetUtil.getTargetsForLocation(this);
        List<PsiAnnotation> filtered = ContainerUtil.findAll(
            getAnnotations(),
            annotation -> {
                PsiAnnotation.TargetType target = AnnotationTargetUtil.findAnnotationTarget(annotation, targets);
                return target != null && target != PsiAnnotation.TargetType.UNKNOWN;
            }
        );

        return filtered.toArray(PsiAnnotation.EMPTY_ARRAY);
    }

    @Override
    public PsiAnnotation findAnnotation(@Nonnull String qualifiedName) {
        return PsiImplUtil.findAnnotation(this, qualifiedName);
    }

    @Override
    @Nonnull
    public PsiAnnotation addAnnotation(@Nonnull String qualifiedName) {
        return (PsiAnnotation)addAfter(
            JavaPsiFacade.getElementFactory(getProject()).createAnnotationFromText("@" + qualifiedName, this),
            null
        );
    }

    @Override
    public void accept(@Nonnull PsiElementVisitor visitor) {
        if (visitor instanceof JavaElementVisitor elemVisitor) {
            elemVisitor.visitModifierList(this);
        }
        else {
            visitor.visitElement(this);
        }
    }

    @Override
    @RequiredReadAction
    public String toString() {
        return "PsiModifierList:" + getText();
    }

    private static class ModifierCache {
        static final Interner<List<String>> ourInterner = Interner.createWeakInterner();
        final PsiFile file;
        final List<String> modifiers;
        final long modCount;

        ModifierCache(@Nonnull PsiFile file, @Nonnull Set<String> modifiers) {
            this.file = file;
            List<String> modifierList = new ArrayList<>(modifiers);
            Collections.sort(modifierList);
            this.modifiers = ourInterner.intern(modifierList);
            this.modCount = getModCount();
        }

        private long getModCount() {
            return file.getManager().getModificationTracker().getModificationCount() + file.getModificationStamp();
        }

        boolean isUpToDate() {
            return getModCount() == modCount;
        }
    }
}