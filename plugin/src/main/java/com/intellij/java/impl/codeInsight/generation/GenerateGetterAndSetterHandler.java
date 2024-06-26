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
package com.intellij.java.impl.codeInsight.generation;

import com.intellij.java.language.impl.codeInsight.generation.GenerationInfo;
import com.intellij.java.language.psi.PsiClass;
import consulo.language.editor.generation.ClassMember;
import consulo.language.editor.localize.CodeInsightLocalize;
import consulo.language.util.IncorrectOperationException;

import java.util.ArrayList;
import java.util.Collections;

public class GenerateGetterAndSetterHandler extends GenerateGetterSetterHandlerBase {
  private final GenerateGetterHandler myGenerateGetterHandler = new GenerateGetterHandler();
  private final GenerateSetterHandler myGenerateSetterHandler = new GenerateSetterHandler();

  public GenerateGetterAndSetterHandler() {
    super(CodeInsightLocalize.generateGetterSetterTitle().get());
  }

  @Override
  public GenerationInfo[] generateMemberPrototypes(PsiClass aClass, ClassMember original) throws IncorrectOperationException {
    ArrayList<GenerationInfo> array = new ArrayList<>();
    GenerationInfo[] getters = myGenerateGetterHandler.generateMemberPrototypes(aClass, original);
    GenerationInfo[] setters = myGenerateSetterHandler.generateMemberPrototypes(aClass, original);

    if (getters.length + setters.length > 0) {
      Collections.addAll(array, getters);
      Collections.addAll(array, setters);
    }

    return array.toArray(new GenerationInfo[array.size()]);
  }

  @Override
  protected String getNothingFoundMessage() {
    return "No fields have been found to generate getters/setters for";
  }

  @Override
  protected String getNothingAcceptedMessage() {
    return "No fields without getter/setter were found";
  }
}