/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.java.execution.impl.jar;

import com.intellij.java.execution.impl.application.BaseJavaApplicationCommandLineState;
import com.intellij.java.execution.impl.util.JavaParametersUtil;
import consulo.execution.runner.ExecutionEnvironment;
import consulo.java.execution.configurations.OwnJavaParameters;
import consulo.process.ExecutionException;
import consulo.util.io.FileUtil;
import jakarta.annotation.Nonnull;

/**
 * @author nik
 */
public class JarApplicationCommandLineState extends BaseJavaApplicationCommandLineState<JarApplicationConfiguration> {
  public JarApplicationCommandLineState(@Nonnull final JarApplicationConfiguration configuration, final ExecutionEnvironment environment) {
    super(environment, configuration);
  }

  @Override
  protected OwnJavaParameters createJavaParameters() throws ExecutionException {
    final OwnJavaParameters params = new OwnJavaParameters();
    final String jreHome = myConfiguration.isAlternativeJrePathEnabled() ? myConfiguration.getAlternativeJrePath() : null;
    params.setJdk(JavaParametersUtil.createProjectJdk(myConfiguration.getProject(), jreHome));
    setupJavaParameters(params);
    params.setJarPath(FileUtil.toSystemDependentName(myConfiguration.getJarPath()));
    return params;
  }
}
