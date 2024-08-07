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

import static org.junit.Assert.*;

import com.intellij.java.language.impl.JavaFileType;
import com.intellij.java.language.psi.PsiClass;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.language.impl.internal.psi.LoadTextUtil;
import consulo.virtualFileSystem.VirtualFile;
import com.intellij.testFramework.PlatformTestCase;
import com.intellij.testFramework.PsiTestCase;
import com.intellij.testFramework.PsiTestUtil;

@PlatformTestCase.WrapInCommand
public abstract class AddClassToFileTest extends PsiTestCase{
  public void test() throws Exception {
    VirtualFile root = PsiTestUtil.createTestProjectStructure(myProject, myModule, myFilesToDelete);
    PsiDirectory dir = myPsiManager.findDirectory(root);
    assertNotNull(dir);
    PsiFile file = dir.createFile("AAA.java");
    PsiClass aClass = myJavaFacade.getElementFactory().createClass("AAA");
    file.add(aClass);

    PsiTestUtil.checkFileStructure(file);
  }

  public void testFileModified() throws Exception {
    VirtualFile root = PsiTestUtil.createTestProjectStructure(myProject, myModule, myFilesToDelete);
    VirtualFile pkg = root.createChildDirectory(this, "foo");
    PsiDirectory dir = myPsiManager.findDirectory(pkg);
    assertNotNull(dir);
    String text = "package foo;\n\nclass A {}";
    PsiElement created = dir.add(PsiFileFactory.getInstance(getProject()).createFileFromText("A.java", JavaFileType.INSTANCE, text));
    VirtualFile virtualFile = created.getContainingFile().getVirtualFile();
    assertNotNull(virtualFile);
    String fileText = LoadTextUtil.loadText(virtualFile).toString();
    assertEquals(text, fileText);

    Document doc = FileDocumentManager.getInstance().getDocument(virtualFile);
    assertNotNull(doc);
    assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
    assertFalse(FileDocumentManager.getInstance().isFileModified(virtualFile));
  }
}
