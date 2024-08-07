package com.intellij.refactoring.moveMethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.annotation.Nonnull;

import com.intellij.JavaTestUtil;
import consulo.language.psi.PsiElement;
import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.language.psi.PsiVariable;
import consulo.language.editor.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.LightRefactoringTestCase;
import com.intellij.java.impl.refactoring.move.moveInstanceMethod.MoveInstanceMethodHandler;
import com.intellij.java.impl.refactoring.move.moveInstanceMethod.MoveInstanceMethodProcessor;
import com.intellij.java.language.util.VisibilityUtil;
import consulo.util.collection.ContainerUtil;
import consulo.language.editor.TargetElementUtil;
import consulo.codeInsight.TargetElementUtilEx;

/**
 * @author ven
 */
public abstract class MoveInstanceMethodTest extends LightRefactoringTestCase {

  public void testSimple() throws Exception { doTest(true, 0); }

  public void testSimpleWithTargetField() throws Exception { doTest(false, 1); }

  public void testInterface() throws Exception { doTest(true, 0); }

  public void testWithInner() throws Exception { doTest(true, 0); }

  public void testJavadoc() throws Exception { doTest(true, 0); }

  public void testRecursive() throws Exception { doTest(true, 0); }

  public void testRecursive1() throws Exception { doTest(true, 0); }

  public void testQualifiedThis() throws Exception { doTest(true, 0); }

  public void testQualifyThisHierarchy() throws Exception {doTest(true, 0);}

  public void testQualifyField() throws Exception {doTest(false, 0);}

  public void testAnonymousHierarchy() throws Exception {doTest(true, 0);}

  public void testTwoParams() throws Exception { doTest(true, 0); }

  public void testNoThisParam() throws Exception { doTest(false, 0); }

  public void testNoGenerics() throws Exception { doTest(false, 0); }

  public void testQualifierToArg1() throws Exception { doTest(true, 0); }

  public void testQualifierToArg2() throws Exception { doTest(true, 0); }

  public void testQualifierToArg3() throws Exception { doTest(true, 0); }

  public void testIDEADEV11257() throws Exception { doTest(true, 0); }

  public void testThisInAnonymous() throws Exception { doTest(true, 0); }

  public void testOverloadingMethods() throws Exception { doTest(true, 0); }
  public void testOverloadingMethods1() throws Exception { doTest(true, 0); }

  public void testPolyadicExpr() throws Exception { doTest(true, 0); }

  public void testEscalateVisibility() throws Exception {
    doTest(true, 0, VisibilityUtil.ESCALATE_VISIBILITY);
  }

  public void testSameNames() throws Exception {
    doTest(true, 0);
  }
  public void testCorrectThisRefs() throws Exception {
    doTest(true, 0);
  }
   
  public void testSameNamesRecursion() throws Exception {
    doTest(true, 0);
  }

  public void testQualifyFieldAccess() throws Exception {
    doTest(false, 0);
  }

  public void testStripFieldQualifier() throws Exception {
    doTest(false, 0);
  }

  public void testMethodReference() throws Exception {
    try {
      doTest(true, 0);
      fail("Conflict was not detected");
    }
    catch (BaseRefactoringProcessor.ConflictsInTestsException e) {
      assertEquals("Method reference would be broken after move", e.getMessage());
    }
  }

  private void doTest(boolean isTargetParameter, final int targetIndex) throws Exception {
    doTest(isTargetParameter, targetIndex, null);
  }

  private void doTest(boolean isTargetParameter, final int targetIndex, final String newVisibility) throws Exception {
    final String filePath = "/refactoring/moveInstanceMethod/" + getTestName(false) + ".java";
    configureByFile(filePath);
    final PsiElement targetElement = TargetElementUtil.findTargetElement(getEditor(), ContainerUtil.newHashSet(TargetElementUtilEx.ELEMENT_NAME_ACCEPTED));
    assertTrue("<caret> is not on method name", targetElement instanceof PsiMethod);
    PsiMethod method = (PsiMethod) targetElement;
    final PsiVariable targetVariable = isTargetParameter ? method.getParameterList().getParameters()[targetIndex] :
                                       method.getContainingClass().getFields()[targetIndex];
    new MoveInstanceMethodProcessor(getProject(),
                                    method, targetVariable, newVisibility, MoveInstanceMethodHandler.suggestParameterNames (method, targetVariable)).run();
    checkResultByFile(filePath + ".after");

  }

  @Nonnull
  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath();
  }

}
