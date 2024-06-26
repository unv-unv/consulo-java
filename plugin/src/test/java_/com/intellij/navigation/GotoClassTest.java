package com.intellij.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import consulo.fileEditor.TextEditor;
import consulo.navigation.NavigationUtil;
import consulo.codeEditor.Editor;
import consulo.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.HeavyFileEditorManagerTestCase;
import consulo.virtualFileSystem.VirtualFile;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiJavaFile;

/**
 * @author Dmitry Avdeev
 *         Date: 4/25/13
 */
public abstract class GotoClassTest extends HeavyFileEditorManagerTestCase {

  public void testGotoClass() throws Exception {

    PsiJavaFile file = (PsiJavaFile)myFixture.configureByText("Foo.java", "public class Foo {\n" +
                                                         "}\n" +
                                                         "\n" +
                                                         "class Bar {}\n");

    VirtualFile virtualFile = file.getVirtualFile();
    assertNotNull(virtualFile);
    myManager.openFile(virtualFile, true);
    assertEquals(0, getOffset(virtualFile));
    myManager.closeAllFiles();

    PsiClass psiClass = file.getClasses()[1];
    int identifierOffset = psiClass.getNameIdentifier().getTextOffset();
    NavigationUtil.activateFileWithPsiElement(psiClass);
    assertEquals(identifierOffset, getOffset(virtualFile));

    getEditor(virtualFile).getCaretModel().moveToOffset(identifierOffset + 3); // it's still inside the class, so keep it

    myManager.closeAllFiles();
    NavigationUtil.activateFileWithPsiElement(psiClass);
    assertEquals(identifierOffset + 3, getOffset(virtualFile));

    getEditor(virtualFile).getCaretModel().moveToOffset(0);
    NavigationUtil.activateFileWithPsiElement(psiClass);
    assertEquals(identifierOffset, getOffset(virtualFile));

    myManager.closeAllFiles();
    NavigationUtil.activateFileWithPsiElement(file); // GoTo file should keep offset
    assertEquals(identifierOffset, getOffset(virtualFile));
  }

  private int getOffset(VirtualFile virtualFile) {
    Editor editor = getEditor(virtualFile);
    return editor.getCaretModel().getOffset();
  }

  private Editor getEditor(VirtualFile virtualFile) {
    FileEditor[] editors = myManager.getEditors(virtualFile);
    return ((TextEditor)editors[0]).getEditor();
  }
}
