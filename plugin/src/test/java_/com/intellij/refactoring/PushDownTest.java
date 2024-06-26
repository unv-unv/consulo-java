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
package com.intellij.refactoring;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import com.intellij.java.language.psi.JavaPsiFacade;
import com.intellij.java.language.psi.PsiClass;
import consulo.language.psi.PsiElement;
import com.intellij.java.language.psi.PsiField;
import com.intellij.java.language.psi.PsiMember;
import consulo.language.psi.scope.GlobalSearchScope;
import com.intellij.java.impl.refactoring.memberPushDown.PushDownProcessor;
import consulo.ide.impl.idea.refactoring.util.DocCommentPolicy;
import com.intellij.java.impl.refactoring.util.classMembers.MemberInfo;
import com.intellij.java.impl.refactoring.util.classMembers.MemberInfoStorage;
import consulo.usage.UsageInfo;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.MultiMap;
import consulo.language.editor.TargetElementUtil;
import consulo.codeInsight.TargetElementUtilEx;

/**
 * @author anna
 * @since 13-Mar-2008
 */
public abstract class PushDownTest extends LightRefactoringTestCase {
  private static final String BASE_PATH = "/refactoring/pushDown/";

  public void testTypeParameter() { doTest(); }
  public void testTypeParameterErasure() { doTest(); }
  public void testFieldTypeParameter() { doTest(); }
  public void testBodyTypeParameter() { doTest(); }
  public void testDisagreeTypeParameter() { doTest(true); }
  public void testFieldAndReferencedClass() { doTest(); }
  public void testFieldAndStaticReferencedClass() { doTest(); }
  public void testThisRefInAnonymous() { doTest(); }
  public void testSuperOverHierarchyConflict() { doTest(true); }
  public void testSuperOverHierarchy() { doTest(); }
  public void testMethodTypeParametersList() { doTest(); }
  public void testMethodFromInterfaceToAbstractClass() { doTest(); }
  public void testOverridingMethodWithSubst() { doTest(); }
  public void testSameClassInterface() { doTestImplements(); }
  public void testPreserveTypeArgs() { doTestImplements(); }
  public void testSubstTypeArgs() { doTestImplements(); }
  public void testExtensionMethodToInterface() { doTest(); }
  public void testExtensionMethodToClass() { doTest(); }

  private void doTest() {
    doTest(false);
  }

  private void doTest(final boolean failure) {
    configureByFile(BASE_PATH + getTestName(false) + ".java");

    final PsiElement targetElement = TargetElementUtil.findTargetElement(getEditor(), ContainerUtil.newHashSet(TargetElementUtilEx.ELEMENT_NAME_ACCEPTED));
    assertTrue("<caret> is not on member name", targetElement instanceof PsiMember);

    final PsiMember psiMember = (PsiMember)targetElement;

    final PsiClass currentClass = psiMember.getContainingClass();

    assert currentClass != null;

    final List<MemberInfo> membersToMove = new ArrayList<MemberInfo>();

    final PsiField fieldByName = currentClass.findFieldByName("fieldToMove", false);
    if (fieldByName != null) {
      final MemberInfo memberInfo = new MemberInfo(fieldByName);
      memberInfo.setChecked(true);
      membersToMove.add(memberInfo);
    }

    final MemberInfo memberInfo = new MemberInfo(psiMember);
    memberInfo.setChecked(true);
    membersToMove.add(memberInfo);

    new PushDownProcessor(getProject(), membersToMove.toArray(new MemberInfo[membersToMove.size()]), currentClass,
                          new DocCommentPolicy(DocCommentPolicy.ASIS)) {
      @Override
      protected boolean showConflicts(MultiMap<PsiElement, String> conflicts, UsageInfo[] usages) {
        if (failure ? conflicts.isEmpty() : !conflicts.isEmpty()) {
          fail(failure ? "Conflict was not detected" : "False conflict was detected");
        }
        return true;
      }
    }.run();

    checkResultByFile(BASE_PATH + getTestName(false) + "_after.java");
  }

  private void doTestImplements() {
    configureByFile(BASE_PATH + getTestName(false) + ".java");

    PsiClass currentClass = JavaPsiFacade.getInstance(getProject()).findClass("Test", GlobalSearchScope.projectScope(getProject()));
    MemberInfoStorage memberInfoStorage = new MemberInfoStorage(currentClass, new MemberInfo.Filter<PsiMember>() {
      @Override
      public boolean includeMember(PsiMember element) {
        return true;
      }
    });
    List<MemberInfo> members = memberInfoStorage.getClassMemberInfos(currentClass);
    for (MemberInfo member : members) {
      member.setChecked(true);
    }

    new PushDownProcessor(getProject(), members.toArray(new MemberInfo[members.size()]), currentClass,
                          new DocCommentPolicy(DocCommentPolicy.ASIS)) {
      @Override
      protected boolean showConflicts(MultiMap<PsiElement, String> conflicts, UsageInfo[] usages) {
        return true;
      }
    }.run();

    checkResultByFile(BASE_PATH + getTestName(false) + "_after.java");
  }
}
