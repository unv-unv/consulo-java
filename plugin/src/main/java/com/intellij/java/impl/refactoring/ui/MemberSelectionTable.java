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

/*
 * Created by IntelliJ IDEA.
 * User: dsl
 * Date: 17.06.2002
 * Time: 16:35:43
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.java.impl.refactoring.ui;

import com.intellij.java.impl.refactoring.util.classMembers.MemberInfo;
import com.intellij.java.language.psi.PsiMember;
import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.language.psi.PsiModifier;
import consulo.application.AllIcons;
import consulo.language.editor.refactoring.classMember.MemberInfoModel;
import consulo.language.editor.refactoring.ui.AbstractMemberSelectionTable;
import consulo.ui.image.Image;
import jakarta.annotation.Nullable;

import java.util.List;

public class MemberSelectionTable extends AbstractMemberSelectionTable<PsiMember, MemberInfo> {

  public MemberSelectionTable(final List<MemberInfo> memberInfos, String abstractColumnHeader) {
    this(memberInfos, null, abstractColumnHeader);
  }

  public MemberSelectionTable(final List<MemberInfo> memberInfos, MemberInfoModel<PsiMember, MemberInfo> memberInfoModel, String abstractColumnHeader) {
    super(memberInfos, memberInfoModel, abstractColumnHeader);
  }

  @Nullable
  @Override
  protected Object getAbstractColumnValue(MemberInfo memberInfo) {
    if (!(memberInfo.getMember() instanceof PsiMethod)) return null;
    if (memberInfo.isStatic()) return null;

    PsiMethod method = (PsiMethod)memberInfo.getMember();
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
      final Boolean fixedAbstract = myMemberInfoModel.isFixedAbstract(memberInfo);
      if (fixedAbstract != null) return fixedAbstract;
    }

    if (!myMemberInfoModel.isAbstractEnabled(memberInfo)) {
      return myMemberInfoModel.isAbstractWhenDisabled(memberInfo);
    }
    else {
      return memberInfo.isToAbstract() ? Boolean.TRUE : Boolean.FALSE;
    }
  }

  @Override
  protected boolean isAbstractColumnEditable(int rowIndex) {
    MemberInfo info = myMemberInfos.get(rowIndex);
    if (!(info.getMember() instanceof PsiMethod)) return false;
    if (info.isStatic()) return false;

    PsiMethod method = (PsiMethod)info.getMember();
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
      if (myMemberInfoModel.isFixedAbstract(info) != null) {
        return false;
      }
    }

    return info.isChecked() && myMemberInfoModel.isAbstractEnabled(info);
  }


//  @Override
//  protected void setVisibilityIcon(MemberInfo memberInfo, RowIcon icon) {
//    PsiMember member = memberInfo.getMember();
//    PsiModifierList modifiers = member != null ? member.getModifierList() : null;
//    if (modifiers != null) {
//      VisibilityIcons.setVisibilityIcon(modifiers, icon);
//    }
//    else {
//      icon.setIcon(IconUtil.getEmptyIcon(true), VISIBILITY_ICON_POSITION);
//    }
//  }

  @Override
  protected Image getOverrideIcon(MemberInfo memberInfo) {
    PsiMember member = memberInfo.getMember();
    Image overrideIcon = MemberSelectionTable.EMPTY_OVERRIDE_ICON;
    if (member instanceof PsiMethod) {
      if (Boolean.TRUE.equals(memberInfo.getOverrides())) {
        overrideIcon = AllIcons.General.OverridingMethod;
      }
      else if (Boolean.FALSE.equals(memberInfo.getOverrides())) {
        overrideIcon = AllIcons.General.ImplementingMethod;
      }
      else {
        overrideIcon = MemberSelectionTable.EMPTY_OVERRIDE_ICON;
      }
    }
    return overrideIcon;
  }
}
