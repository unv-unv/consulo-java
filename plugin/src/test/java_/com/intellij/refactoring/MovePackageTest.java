package com.intellij.refactoring;

import com.intellij.java.impl.refactoring.PackageWrapper;
import consulo.document.FileDocumentManager;
import consulo.virtualFileSystem.VirtualFile;
import com.intellij.java.language.psi.JavaPsiFacade;
import consulo.language.psi.PsiDirectory;
import com.intellij.java.language.psi.PsiJavaPackage;
import com.intellij.java.impl.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor;
import com.intellij.java.impl.refactoring.move.moveClassesOrPackages.SingleSourceRootMoveDestination;
import com.intellij.JavaTestUtil;

public abstract class MovePackageTest extends MultiFileTestCase {

  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath();
  }

  public void testMoveSingle() throws Exception {
    doTest(new String[]{"pack1"}, "target");
  }

/* IMPLEMENT: soft references in JSP
  public void testJsp() throws Exception {
    doTest(new String[]{"pack1"}, "target");
  }
*/
  public void testQualifiedRef() throws Exception {
    doTest(new String[]{"package1.test"}, "package2");
  }

  public void testInsidePackage() throws Exception {
    doTest(new String[]{"a"}, "a.b");
  }

  @Override
  protected String getTestRoot() {
    return "/refactoring/movePackage/";
  }

  private void doTest(final String[] packageNames, final String newPackageName) throws Exception {
    doTest(new PerformAction() {
      @Override
      public void performAction(VirtualFile rootDir, VirtualFile rootAfter) throws Exception {
        MovePackageTest.this.performAction(packageNames, newPackageName);
      }
    });
  }

  private void performAction(String[] packageNames, String newPackageName) throws Exception {
    final PsiJavaPackage[] packages = new PsiJavaPackage[packageNames.length];
    for (int i = 0; i < packages.length; i++) {
      String packageName = packageNames[i];
      packages[i] = JavaPsiFacade.getInstance(myPsiManager.getProject()).findPackage(packageName);
      assertNotNull("Package " + packageName + " not found", packages[i]);
    }

    PsiJavaPackage newParentPackage = JavaPsiFacade.getInstance(myPsiManager.getProject()).findPackage(newPackageName);
    assertNotNull(newParentPackage);
    final PsiDirectory[] dirs = newParentPackage.getDirectories();
    assertEquals(dirs.length, 1);

    new MoveClassesOrPackagesProcessor(myProject, packages,
                                       new SingleSourceRootMoveDestination(PackageWrapper.create(newParentPackage), dirs[0]),
                                       true, false, null).run();
    FileDocumentManager.getInstance().saveAllDocuments();
  }
}
