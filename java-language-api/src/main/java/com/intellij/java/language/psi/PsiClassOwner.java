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

import consulo.language.psi.PsiFile;
import consulo.language.util.IncorrectOperationException;

import jakarta.annotation.Nonnull;

/**
 * @author ven
 */
public interface PsiClassOwner extends PsiFile {
  /**
   * @return classes owned by this element.
   */
  @Nonnull
  PsiClass[] getClasses();

  /**
   * Returns the name of the package to which the file belongs.
   *
   * @return the name specified in the package statement, or an empty string for a JSP page or
   * file which has no package statement.
   */
  String getPackageName();

  void setPackageName(String packageName) throws IncorrectOperationException;
}
