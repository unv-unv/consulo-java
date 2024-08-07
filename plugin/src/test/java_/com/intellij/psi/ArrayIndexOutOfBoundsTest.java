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
package com.intellij.psi;

import com.intellij.JavaTestUtil;
import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiJavaPackage;
import consulo.language.ast.ASTNode;
import consulo.application.ApplicationManager;
import consulo.undoRedo.CommandProcessor;
import consulo.document.FileDocumentManager;
import consulo.ide.impl.idea.openapi.util.io.FileUtil;
import consulo.language.impl.psi.SourceTreeToPsiMap;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.language.editor.refactoring.rename.RenameProcessor;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.PsiTestCase;
import com.intellij.testFramework.PsiTestUtil;
import consulo.logging.Logger;

import java.io.File;
import java.io.IOException;

/**
 * @author max
 */
public abstract class ArrayIndexOutOfBoundsTest extends PsiTestCase {
  private static final Logger LOG = Logger.getInstance(ArrayIndexOutOfBoundsTest.class);
  private VirtualFile myProjectRoot;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    String root = JavaTestUtil.getJavaTestDataPath() + "/psi/arrayIndexOutOfBounds/src";
    PsiTestUtil.removeAllRoots(myModule, IdeaTestUtil.getMockJdk17());
    myProjectRoot = PsiTestUtil.createTestProjectStructure(myProject, myModule, root, myFilesToDelete);
  }

  public void testSCR10930() throws Exception {
    renamePackage();
    deleteNewPackage();
    restoreSources();
    renamePackage();
  }

  public void testSimplerCase() throws Exception {
    renamePackage();
    restoreSources();

    PsiFile psiFile = myPsiManager.findFile(myProjectRoot.findFileByRelativePath("bla/Bla.java"));
    assertNotNull(psiFile);

    assertEquals(4, psiFile.getChildren().length);
  }

  public void testLongLivingClassAfterRename() throws Exception {
    PsiClass psiClass = myJavaFacade.findClass("bla.Bla", GlobalSearchScope.projectScope(getProject()));
    ASTNode treeElement = SourceTreeToPsiMap.psiElementToTree(psiClass);
    renamePackage();
    //assertTrue(psiClass.isValid());
    SourceTreeToPsiMap.treeElementToPsi(treeElement);
  }

  private void restoreSources() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        try {
          FileUtil.copyDir(new File(JavaTestUtil.getJavaTestDataPath() + "/psi/arrayIndexOutOfBounds/src"),
                           VfsUtilCore.virtualToIoFile(myProjectRoot));
        }
        catch (IOException e) {
          LOG.error(e);
        }
        VirtualFileManager.getInstance().syncRefresh();
      }
    };
    CommandProcessor.getInstance().executeCommand(myProject, runnable,  "", null);
  }

  private void deleteNewPackage() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        final PsiJavaPackage aPackage = JavaPsiFacade.getInstance(myPsiManager.getProject()).findPackage("anotherBla");
        assertNotNull("Package anotherBla not found", aPackage);
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            aPackage.getDirectories()[0].delete();
          }
        });
        VirtualFileManager.getInstance().syncRefresh();
      }
    };
    CommandProcessor.getInstance().executeCommand(myProject, runnable,  "", null);
  }

  private void renamePackage() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        PsiJavaPackage aPackage = JavaPsiFacade.getInstance(myPsiManager.getProject()).findPackage("bla");
        assertNotNull("Package bla not found", aPackage);

        PsiDirectory dir = aPackage.getDirectories()[0];
        new RenameProcessor(myProject, dir, "anotherBla", true, true).run();
        FileDocumentManager.getInstance().saveAllDocuments();
      }
    };
    CommandProcessor.getInstance().executeCommand(myProject, runnable,  "", null);
  }
}
