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
package com.intellij.java.language.psi;

import jakarta.annotation.Nullable;

/**
 * Represents a Java <code>import static</code> statement.
 *
 * @author dsl
 */
public interface PsiImportStaticStatement extends PsiImportStatementBase {
  /**
   * The empty array of PSI static import statements which can be reused to avoid unnecessary allocations.
   */
  PsiImportStaticStatement[] EMPTY_ARRAY = new PsiImportStaticStatement[0];

  /**
   * Resolves the reference to the class from which members are imported.
   *
   * @return the class from which members are imported, or null if the reference resolve failed
   * or the resolve target is not a class.
   */
  @Nullable
  PsiClass resolveTargetClass();

  /**
   * Returns the name of the member imported from the statement.
   *
   * @return the name of the member, or null for an on-demand import.
   */
  @Nullable
  String getReferenceName();
}
