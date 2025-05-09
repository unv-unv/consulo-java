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
package com.intellij.java.compiler.artifact.impl.artifacts;

import com.intellij.java.compiler.artifact.impl.ManifestFileUtil;
import com.intellij.java.compiler.artifact.impl.ui.ManifestFileConfiguration;
import consulo.compiler.artifact.ArtifactType;
import consulo.compiler.artifact.element.CompositePackagingElement;
import consulo.compiler.artifact.element.PackagingElementResolvingContext;
import consulo.logging.Logger;
import consulo.util.io.FileUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;

import jakarta.annotation.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ManifestFilesInfo {
    private static final Logger LOG = Logger.getInstance(ManifestFilesInfo.class);
    private final Map<VirtualFile, ManifestFileConfiguration> myManifestFiles = new HashMap<>();
    private final Map<VirtualFile, ManifestFileConfiguration> myOriginalManifestFiles = new HashMap<>();

    @Nullable
    public ManifestFileConfiguration getManifestFile(
        CompositePackagingElement<?> element,
        ArtifactType artifactType,
        PackagingElementResolvingContext context
    ) {
        VirtualFile manifestFile = ManifestFileUtil.findManifestFile(element, context, artifactType);
        if (manifestFile == null) {
            return null;
        }

        ManifestFileConfiguration configuration = myManifestFiles.get(manifestFile);
        if (configuration == null) {
            configuration = ManifestFileUtil.createManifestFileConfiguration(manifestFile);
            myOriginalManifestFiles.put(manifestFile, new ManifestFileConfiguration(configuration));
            myManifestFiles.put(manifestFile, configuration);
        }
        return configuration;
    }

    public void saveManifestFiles() {
        for (Map.Entry<VirtualFile, ManifestFileConfiguration> entry : myManifestFiles.entrySet()) {
            ManifestFileConfiguration configuration = entry.getValue();
            String path = configuration.getManifestFilePath();
            if (path == null) {
                continue;
            }

            ManifestFileConfiguration original = myOriginalManifestFiles.get(entry.getKey());
            if (original != null && original.equals(configuration)) {
                continue;
            }

            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
            if (file == null) {
                File ioFile = new File(FileUtil.toSystemDependentName(path));
                FileUtil.createIfDoesntExist(ioFile);
                file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
                if (file == null) {
                    //todo[nik] improve
                    LOG.error("cannot create file: " + ioFile);
                }
            }

            ManifestFileUtil.updateManifest(file, configuration.getMainClass(), configuration.getClasspath(), true);
        }
    }

    public boolean isManifestFilesModified() {
        return !myOriginalManifestFiles.equals(myManifestFiles);
    }

    public void clear() {
        myManifestFiles.clear();
        myOriginalManifestFiles.clear();
    }
}
