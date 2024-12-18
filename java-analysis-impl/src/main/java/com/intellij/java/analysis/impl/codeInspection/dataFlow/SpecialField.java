// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.analysis.impl.codeInspection.dataFlow;

import com.intellij.java.analysis.impl.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.types.DfConstantType;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.types.DfReferenceType;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.types.DfType;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.types.DfTypes;
import com.intellij.java.analysis.impl.codeInspection.dataFlow.value.*;
import com.intellij.java.analysis.impl.codeInspection.util.OptionalUtil;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.util.InheritanceUtil;
import com.intellij.java.language.psi.util.PsiUtil;
import com.intellij.java.language.psi.util.TypeConversionUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import consulo.java.analysis.localize.JavaAnalysisLocalize;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import consulo.util.lang.ObjectUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nls;

import java.util.Objects;

import static com.intellij.java.analysis.impl.codeInspection.dataFlow.ContractReturnValue.returnFalse;
import static com.intellij.java.analysis.impl.codeInspection.dataFlow.ContractReturnValue.returnTrue;
import static com.intellij.java.analysis.impl.codeInspection.dataFlow.StandardMethodContract.ValueConstraint.NULL_VALUE;
import static com.intellij.java.language.psi.CommonClassNames.*;

/**
 * Represents a method which is handled as a field in DFA.
 *
 * @author Tagir Valeev
 */
public enum SpecialField implements VariableDescriptor {
    ARRAY_LENGTH("length", JavaAnalysisLocalize.specialFieldArrayLength(), true) {
        @Override
        boolean isMyQualifierType(PsiType type) {
            return type instanceof PsiArrayType;
        }

        @Override
        boolean isMyAccessor(PsiMember accessor) {
            return accessor instanceof PsiField && "length".equals(accessor.getName()) && PsiUtil.isArrayClass(accessor.getContainingClass());
        }

        @Nonnull
        @Override
        DfType fromInitializer(PsiExpression initializer) {
            if (initializer instanceof PsiArrayInitializerExpression arrayInitializer) {
                return DfTypes.intValue(arrayInitializer.getInitializers().length);
            }
            if (initializer instanceof PsiNewExpression newExpr) {
                PsiArrayInitializerExpression arrayInitializer = newExpr.getArrayInitializer();
                if (arrayInitializer != null) {
                    return DfTypes.intValue(arrayInitializer.getInitializers().length);
                }
                PsiExpression[] dimensions = newExpr.getArrayDimensions();
                if (dimensions.length > 0 && ExpressionUtils.computeConstantExpression(dimensions[0]) instanceof Integer length) {
                    return DfTypes.intValue(length);
                }
            }
            return DfTypes.TOP;
        }
    },
    STRING_LENGTH("length", JavaAnalysisLocalize.specialFieldStringLength(), true) {
        @Nonnull
        @Override
        DfType fromInitializer(PsiExpression initializer) {
            return fromConstant(ExpressionUtils.computeConstantExpression(initializer));
        }

        @Override
        boolean isMyQualifierType(PsiType type) {
            return TypeUtils.isJavaLangString(type);
        }

        @Override
        boolean isMyAccessor(PsiMember accessor) {
            if (!(accessor instanceof PsiMethod)
                || !"length".equals(accessor.getName())
                || !((PsiMethod)accessor).getParameterList().isEmpty()) {
                return false;
            }
            PsiClass containingClass = accessor.getContainingClass();
            return containingClass != null && JAVA_LANG_STRING.equals(containingClass.getQualifiedName());
        }

        @Nonnull
        @Override
        public DfType fromConstant(@Nullable Object obj) {
            return obj instanceof String string ? DfTypes.intValue(string.length()) : DfTypes.TOP;
        }
    },
    COLLECTION_SIZE("size", JavaAnalysisLocalize.specialFieldCollectionSize(), false) {
        private final CallMatcher SIZE_METHODS = CallMatcher.anyOf(
            CallMatcher.instanceCall(JAVA_UTIL_COLLECTION, "size").parameterCount(0),
            CallMatcher.instanceCall(JAVA_UTIL_MAP, "size").parameterCount(0)
        );
        private final CallMatcher MAP_COLLECTIONS =
            CallMatcher.instanceCall(JAVA_UTIL_MAP, "keySet", "entrySet", "values").parameterCount(0);

        @Override
        boolean isMyQualifierType(PsiType type) {
            PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(type);
            return psiClass != null && !InheritanceUtil.processSupers(
                psiClass,
                true,
                cls -> {
                    String qualifiedName = cls.getQualifiedName();
                    return !JAVA_UTIL_MAP.equals(qualifiedName) && !JAVA_UTIL_COLLECTION.equals(qualifiedName);
                }
            );
        }

        @Override
        boolean isMyAccessor(PsiMember accessor) {
            return accessor instanceof PsiMethod && SIZE_METHODS.methodMatches((PsiMethod)accessor);
        }

        @Nonnull
        @Override
        public DfType fromConstant(@Nullable Object obj) {
            if (obj instanceof PsiField && DfaUtil.isEmptyCollectionConstantField((PsiVariable)obj)) {
                return DfTypes.intValue(0);
            }
            return super.fromConstant(obj);
        }

        @Nonnull
        @Override
        public DfaValue createValue(@Nonnull DfaValueFactory factory, @Nullable DfaValue qualifier, boolean forAccessor) {
            if (qualifier instanceof DfaVariableValue var
                && var.getQualifier() != null
                && var.getPsiVariable() instanceof PsiMethod method
                && MAP_COLLECTIONS.methodMatches(method)) {
                return super.createValue(factory, var.getQualifier(), forAccessor);
            }
            return super.createValue(factory, qualifier, forAccessor);
        }
    },
    UNBOX("value", JavaAnalysisLocalize.specialFieldUnboxedValue(), true) {
        private final CallMatcher UNBOXING_CALL = CallMatcher.anyOf(
            CallMatcher.exactInstanceCall(JAVA_LANG_INTEGER, "intValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_LONG, "longValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_SHORT, "shortValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_BYTE, "byteValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_CHARACTER, "charValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_BOOLEAN, "booleanValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_FLOAT, "floatValue").parameterCount(0),
            CallMatcher.exactInstanceCall(JAVA_LANG_DOUBLE, "doubleValue").parameterCount(0)
        );

        @Override
        public PsiPrimitiveType getType(DfaVariableValue variableValue) {
            return PsiPrimitiveType.getUnboxedType(variableValue.getType());
        }

        @Nonnull
        @Override
        public DfType getDefaultValue(boolean forAccessor) {
            return DfTypes.TOP;
        }

        @Nonnull
        @Override
        public DfaValue createValue(@Nonnull DfaValueFactory factory, @Nullable DfaValue qualifier, boolean forAccessor) {
            return qualifier instanceof DfaBoxedValue boxedValue
                ? boxedValue.getWrappedValue()
                : super.createValue(factory, qualifier, forAccessor);
        }

        @Override
        boolean isMyQualifierType(PsiType type) {
            return TypeConversionUtil.isPrimitiveWrapper(type);
        }

        @Override
        boolean isMyAccessor(PsiMember accessor) {
            return accessor instanceof PsiMethod && UNBOXING_CALL.methodMatches((PsiMethod)accessor);
        }
    },
    OPTIONAL_VALUE("value", JavaAnalysisLocalize.specialFieldOptionalValue(), true) {
        @Override
        public PsiType getType(DfaVariableValue variableValue) {
            PsiType optionalType = variableValue.getType();
            PsiType type = OptionalUtil.getOptionalElementType(optionalType);
            return type instanceof PsiPrimitiveType primitiveType
                ? primitiveType.getBoxedType(Objects.requireNonNull(((PsiClassType)optionalType).resolve()))
                : type;
        }

        @Nonnull
        @Override
        public DfType getDefaultValue(boolean forAccessor) {
            return (forAccessor ? DfaNullability.NOT_NULL : DfaNullability.NULLABLE).asDfType();
        }

        @Override
        boolean isMyQualifierType(PsiType type) {
            return TypeUtils.isOptional(type);
        }

        @Override
        public String getPresentationText(@Nonnull DfType dfType, @Nullable PsiType type) {
            if (dfType == DfTypes.NULL) {
                return JavaAnalysisLocalize.dftypePresentationEmptyOptional().get();
            }
            if ((!dfType.isSuperType(DfTypes.NULL))) {
                return JavaAnalysisLocalize.dftypePresentationPresentOptional().get();
            }
            return "";
        }

        @Override
        boolean isMyAccessor(PsiMember accessor) {
            return accessor instanceof PsiMethod method && OptionalUtil.OPTIONAL_GET.methodMatches(method);
        }
    };

    private static final SpecialField[] VALUES = values();
    private final String myTitle;
    private final LocalizeValue myTitleValue;
    private final boolean myFinal;

    SpecialField(String title, LocalizeValue titleValue, boolean isFinal) {
        myTitle = title;
        myTitleValue = titleValue;
        myFinal = isFinal;
    }

    @Override
    public boolean isStable() {
        return myFinal;
    }

    abstract boolean isMyQualifierType(PsiType type);

    /**
     * Checks whether supplied accessor (field or method) can be used to read this special field
     *
     * @param accessor accessor to test to test
     * @return true if supplied accessor can be used to read this special field
     */
    abstract boolean isMyAccessor(PsiMember accessor);

    public
    @Nls
    String getPresentationText(@Nonnull DfType dfType, @Nullable PsiType type) {
        return getDefaultValue(false).equals(dfType) ? "" : dfType.toString();
    }

    /**
     * Finds a special field which corresponds to given accessor (method or field)
     *
     * @param accessor accessor to find a special field for
     * @return found special field or null if accessor cannot be used to access a special field
     */
    @Contract("null -> null")
    @Nullable
    public static SpecialField findSpecialField(PsiElement accessor) {
        if (!(accessor instanceof PsiMember)) {
            return null;
        }
        PsiMember member = (PsiMember)accessor;
        for (SpecialField sf : VALUES) {
            if (sf.isMyAccessor(member)) {
                return sf;
            }
        }
        return null;
    }

    /**
     * Returns a DfaValue which represents this special field
     *
     * @param factory   a factory to create new values if necessary
     * @param qualifier a known qualifier value
     * @return a DfaValue which represents this special field
     */
    @Override
    @Nonnull
    public final DfaValue createValue(@Nonnull DfaValueFactory factory, @Nullable DfaValue qualifier) {
        return createValue(factory, qualifier, false);
    }

    @Nonnull
    @Override
    public DfaValue createValue(@Nonnull DfaValueFactory factory, @Nullable DfaValue qualifier, boolean forAccessor) {
        if (qualifier instanceof DfaVariableValue variableValue) {
            PsiModifierListOwner psiVariable = variableValue.getPsiVariable();
            if (psiVariable instanceof PsiField field
                && factory.canTrustFieldInitializer(field)
                && field.isStatic() && field.isFinal()) {
                PsiExpression initializer = field.getInitializer();
                if (initializer != null) {
                    DfType dfType = fromInitializer(initializer);
                    if (dfType != DfTypes.TOP) {
                        return factory.fromDfType(dfType);
                    }
                }
            }
            return VariableDescriptor.super.createValue(factory, qualifier, forAccessor);
        }
        DfType dfType = qualifier == null ? DfTypes.TOP : getFromQualifier(qualifier.getDfType());
        return factory.fromDfType(dfType.meet(getDefaultValue(forAccessor)));
    }

    /**
     * Returns a dfType that describes any possible value this special field may have
     *
     * @param forAccessor if true, the default value for accessor result should be returned
     *                    (may differ from internal representation of value)
     * @return a dfType for the default value
     */
    @Nonnull
    public DfType getDefaultValue(boolean forAccessor) {
        return DfTypes.intRange(LongRangeSet.indexRange());
    }

    @Override
    public PsiType getType(DfaVariableValue variableValue) {
        return PsiType.INT;
    }

    @Nonnull
    DfType fromInitializer(PsiExpression initializer) {
        return DfTypes.TOP;
    }

    @Nonnull
    public DfType fromConstant(@Nullable Object obj) {
        return DfTypes.TOP;
    }

    /**
     * @return a list of method contracts which equivalent to checking this special field for zero
     */
    public MethodContract[] getEmptyContracts() {
        ContractValue thisValue = ContractValue.qualifier().specialField(this);
        return new MethodContract[]{
            MethodContract.singleConditionContract(thisValue, RelationType.EQ, ContractValue.zero(), returnTrue()),
            MethodContract.trivialContract(returnFalse())
        };
    }

    public MethodContract[] getEqualsContracts() {
        return new MethodContract[]{
            new StandardMethodContract(new StandardMethodContract.ValueConstraint[]{NULL_VALUE}, returnFalse()),
            MethodContract.singleConditionContract(
                ContractValue.qualifier().specialField(this), RelationType.NE,
                ContractValue.argument(0).specialField(this), returnFalse()
            )
        };
    }

    /**
     * @param fieldValue dfType of the special field value
     * @return a dfType that represents a value having this special field restricted to the supplied dfType
     */
    @Nonnull
    public DfType asDfType(@Nonnull DfType fieldValue) {
        DfType defaultType = this == OPTIONAL_VALUE ? DfTypes.OBJECT_OR_NULL : getDefaultValue(false);
        DfType clamped = fieldValue.meet(defaultType);
        if (clamped.equals(defaultType)) {
            return DfTypes.NOT_NULL_OBJECT;
        }
        if (clamped.equals(DfTypes.BOTTOM)) {
            return DfTypes.BOTTOM;
        }
        return DfTypes.customObject(TypeConstraints.TOP, DfaNullability.NOT_NULL, Mutability.UNKNOWN, this, clamped);
    }

    /**
     * @param fieldValue      dfType of the special field value
     * @param exactResultType exact PSI type of the result
     * @return a dfType that represents a value having this special field restricted to the supplied dfType
     */
    @Nonnull
    public DfType asDfType(@Nonnull DfType fieldValue, @Nullable PsiType exactResultType) {
        DfType dfType = asDfType(fieldValue);
        if (exactResultType == null) {
            return dfType;
        }
        if (this == STRING_LENGTH && DfConstantType.isConst(fieldValue, 0)) {
            return DfTypes.constant("", exactResultType);
        }
        return dfType.meet(TypeConstraints.exact(exactResultType).asDfType());
    }

    /**
     * Returns a DfType from given DfType qualifier if it's bound to this special field
     *
     * @param dfType of the qualifier
     * @return en extracted DfType
     */
    @Nonnull
    public DfType getFromQualifier(@Nonnull DfType dfType) {
        if (dfType == DfTypes.TOP) {
            return DfTypes.TOP;
        }
        if (!(dfType instanceof DfReferenceType)) {
            return DfTypes.BOTTOM;
        }
        SpecialField sf = ((DfReferenceType)dfType).getSpecialField();
        if (sf == null) {
            return DfTypes.TOP;
        }
        if (sf != this) {
            return DfTypes.BOTTOM;
        }
        return ((DfReferenceType)dfType).getSpecialFieldType();
    }

    /**
     * Returns a special field which corresponds to given qualifier type
     * (currently it's assumed that only one special field may exist for given qualifier type)
     *
     * @param type a qualifier type
     * @return a special field; null if no special field is available for given type
     */
    @Contract("null -> null")
    @Nullable
    public static SpecialField fromQualifierType(PsiType type) {
        if (type == null) {
            return null;
        }
        for (SpecialField value : VALUES) {
            if (value.isMyQualifierType(type)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Returns a special field which corresponds to given qualifier
     *
     * @param value a qualifier value
     * @return a special field; null if no special field is detected to be related to given qualifier
     */
    @Nullable
    public static SpecialField fromQualifier(@Nonnull DfaValue value) {
        DfReferenceType dfType = ObjectUtil.tryCast(value.getDfType(), DfReferenceType.class);
        if (dfType != null && dfType.getSpecialField() != null) {
            return dfType.getSpecialField();
        }
        return fromQualifierType(value.getType());
    }

    @Nonnull
    public String getPresentationName() {
        return myTitleValue.get();
    }

    @Override
    public String toString() {
        return myTitle;
    }
}
