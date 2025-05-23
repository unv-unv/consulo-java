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
package com.intellij.java.compiler.artifact.impl.ui;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author nik
 */
public class ManifestFileConfiguration {
    private final boolean myWritable;
    private List<String> myClasspath = new ArrayList<>();
    private String myMainClass;
    private String myManifestFilePath;

    public ManifestFileConfiguration(@Nonnull ManifestFileConfiguration configuration) {
        myWritable = configuration.isWritable();
        myClasspath.addAll(configuration.getClasspath());
        myMainClass = configuration.getMainClass();
        myManifestFilePath = configuration.getManifestFilePath();
    }

    public ManifestFileConfiguration(
        @Nonnull String manifestFilePath,
        @Nullable List<String> classpath,
        @Nullable String mainClass,
        boolean isWritable
    ) {
        myWritable = isWritable;
        if (classpath != null) {
            myClasspath.addAll(classpath);
        }
        myMainClass = mainClass;
        myManifestFilePath = manifestFilePath;
    }

    public List<String> getClasspath() {
        return myClasspath;
    }

    public boolean isWritable() {
        return myWritable;
    }

    public void setClasspath(List<String> classpath) {
        myClasspath = classpath;
    }

    public String getMainClass() {
        return myMainClass;
    }

    public void setMainClass(String mainClass) {
        myMainClass = mainClass;
    }

    public String getManifestFilePath() {
        return myManifestFilePath;
    }

    public void setManifestFilePath(String manifestFilePath) {
        myManifestFilePath = manifestFilePath;
    }

    @Override
    public boolean equals(Object o) {
        return this == o
            || o instanceof ManifestFileConfiguration that
            && myClasspath.equals(that.myClasspath)
            && Objects.equals(myMainClass, that.myMainClass)
            && Objects.equals(myManifestFilePath, that.myManifestFilePath);
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException();
    }

    public void addToClasspath(List<String> classpath) {
        for (String path : classpath) {
            if (!myClasspath.contains(path)) {
                myClasspath.add(path);
            }
        }
    }
}
