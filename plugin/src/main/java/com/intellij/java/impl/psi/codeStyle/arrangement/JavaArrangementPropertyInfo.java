/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.java.impl.psi.codeStyle.arrangement;

import jakarta.annotation.Nullable;

/**
 * @author Denis Zhdanov
 * @since 9/18/12 3:33 PM
 */
public class JavaArrangementPropertyInfo {

  @Nullable
  private JavaElementArrangementEntry myGetter;
  @Nullable
  private JavaElementArrangementEntry mySetter;

  @Nullable
  public JavaElementArrangementEntry getGetter() {
    return myGetter;
  }

  public void setGetter(@Nullable JavaElementArrangementEntry getter) {
    myGetter = getter;
  }

  @Nullable
  public JavaElementArrangementEntry getSetter() {
    return mySetter;
  }

  public void setSetter(@Nullable JavaElementArrangementEntry setter) {
    mySetter = setter;
  }
}
