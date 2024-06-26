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
package com.intellij.java.impl.refactoring.move.moveClassesOrPackages;

import com.intellij.java.language.psi.PsiJavaPackage;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import consulo.language.psi.*;
import com.intellij.java.impl.refactoring.PackageWrapper;
import com.intellij.java.impl.refactoring.util.RefactoringUtil;
import consulo.usage.UsageInfo;
import consulo.language.util.IncorrectOperationException;
import consulo.util.collection.MultiMap;
import consulo.logging.Logger;

import jakarta.annotation.Nullable;
import java.util.Collection;

/**
 *  @author dsl
 */
public class MultipleRootsMoveDestination extends AutocreatingMoveDestination {
  private static final Logger LOG = Logger.getInstance(
		  MultipleRootsMoveDestination.class);

  public MultipleRootsMoveDestination(PackageWrapper aPackage) {
    super(aPackage);
  }

  public PackageWrapper getTargetPackage() {
    return myPackage;
  }


  public PsiDirectory getTargetDirectory(PsiDirectory source) throws IncorrectOperationException {
    //if (JavaDirectoryService.getInstance().isSourceRoot(source)) return null;
    return getOrCreateDirectoryForSource(source.getVirtualFile());
  }

  public PsiDirectory getTargetDirectory(PsiFile source) throws IncorrectOperationException {
    return getOrCreateDirectoryForSource(source.getVirtualFile());
  }

  public PsiDirectory getTargetIfExists(PsiFile source) {
    return findTargetDirectoryForSource(source.getVirtualFile());
  }

  public String verify(PsiFile source) {
    VirtualFile virtualFile = source.getVirtualFile();
    if (virtualFile.isDirectory()) {
      virtualFile = virtualFile.getParent();
      LOG.assertTrue(virtualFile.isDirectory());
    }

    final VirtualFile sourceRootForFile = myFileIndex.getSourceRootForFile(virtualFile);
    if (sourceRootForFile == null) {
      return "";
    }
    return checkCanCreateInSourceRoot(sourceRootForFile);
  }

  @Nullable
  public String verify(PsiDirectory source) {
    VirtualFile virtualFile = source.getVirtualFile();
    final VirtualFile sourceRootForFile = myFileIndex.getSourceRootForFile(virtualFile);
    if (sourceRootForFile == null) {
      return "";
    }
    if (virtualFile.equals(sourceRootForFile)) return null;
    return checkCanCreateInSourceRoot(sourceRootForFile);
  }

  @Nullable
  public String verify(PsiJavaPackage source) {
    PsiDirectory[] directories = source.getDirectories();
    for (final PsiDirectory directory : directories) {
      String s = verify(directory);
      if (s != null) return s;
    }
    return null;
  }

  public void analyzeModuleConflicts(final Collection<PsiElement> elements,
                                     MultiMap<PsiElement,String> conflicts, final UsageInfo[] usages) {
  }

  @Override
  public boolean isTargetAccessible(Project project, VirtualFile place) {
    return true;
  }

  public PsiDirectory getTargetIfExists(PsiDirectory source) {
    return findTargetDirectoryForSource(source.getVirtualFile());
  }


  private PsiDirectory findTargetDirectoryForSource(final VirtualFile file) {
    final VirtualFile sourceRoot = myFileIndex.getSourceRootForFile(file);
    LOG.assertTrue(sourceRoot != null);
    return RefactoringUtil.findPackageDirectoryInSourceRoot(myPackage, sourceRoot);
  }

  private PsiDirectory getOrCreateDirectoryForSource(final VirtualFile file)
    throws IncorrectOperationException {
    final VirtualFile sourceRoot = myFileIndex.getSourceRootForFile(file);
    LOG.assertTrue(sourceRoot != null);
    return RefactoringUtil.createPackageDirectoryInSourceRoot(myPackage, sourceRoot);
  }
}
