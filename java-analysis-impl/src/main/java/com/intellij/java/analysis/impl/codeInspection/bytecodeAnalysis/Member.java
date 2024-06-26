// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.analysis.impl.codeInspection.bytecodeAnalysis;

import consulo.internal.org.objectweb.asm.tree.MethodInsnNode;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.security.MessageDigest;

public final class Member implements MemberDescriptor
{
	final String internalClassName;
	final String methodName;
	final String methodDesc;

	/**
	 * Primary constructor
	 *
	 * @param internalClassName class name in asm format
	 * @param methodName        method name
	 * @param methodDesc        method descriptor in asm format
	 */
	public Member(@Nonnull String internalClassName, @Nonnull String methodName, @Nonnull String methodDesc)
	{
		this.internalClassName = internalClassName;
		this.methodName = methodName;
		this.methodDesc = methodDesc;
	}

	/**
	 * Convenient constructor to convert asm instruction into method key
	 *
	 * @param mNode asm node from which method key is extracted
	 */
	public Member(MethodInsnNode mNode)
	{
		this.internalClassName = mNode.owner;
		this.methodName = mNode.name;
		this.methodDesc = mNode.desc;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o)
			return true;
		if(o == null || getClass() != o.getClass())
			return false;
		Member method = (Member) o;
		return internalClassName.equals(method.internalClassName) && methodDesc.equals(method.methodDesc) && methodName.equals(method.methodName);
	}

	@Override
	public int hashCode()
	{
		int result = internalClassName.hashCode();
		result = 31 * result + methodName.hashCode();
		result = 31 * result + methodDesc.hashCode();
		return result;
	}

	@Nonnull
	@Override
	public HMember hashed(@Nullable MessageDigest md)
	{
		return new HMember(this, md);
	}

	@Override
	public String toString()
	{
		return internalClassName + ' ' + methodName + ' ' + methodDesc;
	}
}