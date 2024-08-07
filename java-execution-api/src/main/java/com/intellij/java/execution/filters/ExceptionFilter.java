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
package com.intellij.java.execution.filters;

import consulo.application.dumb.DumbAware;
import consulo.execution.ui.console.Filter;
import consulo.language.psi.scope.GlobalSearchScope;

import jakarta.annotation.Nonnull;

public class ExceptionFilter implements Filter, DumbAware {
  private final ExceptionInfoCache myCache;

  public ExceptionFilter(@Nonnull final GlobalSearchScope scope) {
    myCache = new ExceptionInfoCache(scope);
  }

  @Override
  public Result applyFilter(final String line, final int textEndOffset) {
    ExceptionWorker worker = new ExceptionWorker(myCache);
    worker.execute(line, textEndOffset);
    return worker.getResult();
  }
}
