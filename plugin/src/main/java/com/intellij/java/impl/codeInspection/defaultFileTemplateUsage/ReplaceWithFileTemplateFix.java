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
package com.intellij.java.impl.codeInspection.defaultFileTemplateUsage;

import consulo.language.editor.inspection.LocalQuickFix;
import consulo.language.editor.inspection.localize.InspectionLocalize;
import jakarta.annotation.Nonnull;

/**
 * @author cdr
 */
public abstract class ReplaceWithFileTemplateFix implements LocalQuickFix {
  @Override
  @Nonnull
  public String getName() {
    return InspectionLocalize.defaultFileTemplateReplaceWithActualFileTemplate().get();
  }

  @Override
  @Nonnull
  public String getFamilyName() {
    return getName();
  }
}
