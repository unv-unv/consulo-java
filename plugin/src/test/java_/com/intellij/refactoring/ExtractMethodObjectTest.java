/*
 * User: anna
 * Date: 06-May-2008
 */
package com.intellij.refactoring;

import static org.junit.Assert.assertTrue;

import jakarta.annotation.Nonnull;

import com.intellij.JavaTestUtil;
import consulo.application.ApplicationManager;
import consulo.language.psi.PsiElement;
import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.impl.refactoring.extractMethodObject.ExtractMethodObjectHandler;
import com.intellij.java.impl.refactoring.extractMethodObject.ExtractMethodObjectProcessor;
import consulo.util.collection.ContainerUtil;
import consulo.language.editor.TargetElementUtil;
import consulo.codeInsight.TargetElementUtilEx;

public abstract class ExtractMethodObjectTest extends LightRefactoringTestCase {
  @Nonnull
  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath();
  }

  private void doTest() throws Exception {
    doTest(true);
  }

  private void doTest(final boolean createInnerClass) throws Exception {
    final String testName = getTestName(false);
    configureByFile("/refactoring/extractMethodObject/" + testName + ".java");
    PsiElement element = TargetElementUtil.findTargetElement(myEditor, ContainerUtil.newHashSet(TargetElementUtilEx.ELEMENT_NAME_ACCEPTED));
    assertTrue(element instanceof PsiMethod);
    final PsiMethod method = (PsiMethod) element;

    final ExtractMethodObjectProcessor processor =
      new ExtractMethodObjectProcessor(getProject(), getEditor(), method.getBody().getStatements(), "InnerClass");
    final ExtractMethodObjectProcessor.MyExtractMethodProcessor extractProcessor = processor.getExtractProcessor();
    processor.setCreateInnerClass(createInnerClass);
    extractProcessor.setShowErrorDialogs(false);
    extractProcessor.prepare();
    extractProcessor.testPrepare();

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        ExtractMethodObjectHandler.run(getProject(), getEditor(), processor, extractProcessor);
      }
    });


    checkResultByFile("/refactoring/extractMethodObject/" + testName + ".java" + ".after");
  }

  public void testStatic() throws Exception {
    doTest();
  }

  public void testStaticTypeParams() throws Exception {
    doTest();
  }

  public void testStaticTypeParamsReturn() throws Exception {
    doTest();
  }

  public void testTypeParamsReturn() throws Exception {
    doTest();
  }

  public void testTypeParams() throws Exception {
    doTest();
  }

  public void testMethodInHierarchy() throws Exception {
    doTest();
  }

  public void testQualifier() throws Exception {
    doTest();
  }

  public void testVarargs() throws Exception {
    doTest();
  }

  public void testFieldUsage() throws Exception {
    doTest();
  }

  public void testMethodInHierarchyReturn() throws Exception {
    doTest();
  }

  public void testStaticTypeParamsReturnNoDelete() throws Exception {
    doTest();
  }

  public void testStaticTypeParamsRecursive() throws Exception {
    doTest();
  }

  public void testRecursion() throws Exception {
    doTest();
  }

  public void testWrapWithObject() throws Exception {
    doTest(false);
  }

  public void testWrapWithObjectRecursive() throws Exception {
    doTest(false);
  }
  
  public void testWithPrivateMethodUsed() throws Exception {
    doTest();
  }
  
  public void testWithPrivateMethodUsed1() throws Exception {
    doTest();
  }

  public void testWithPrivateMethodWhichCantBeMoved() throws Exception {
    doTest();
  }
}
