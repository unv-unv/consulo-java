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
package com.intellij.java.language.impl.psi.impl.source.tree.java;

import com.intellij.java.language.impl.psi.impl.source.tree.ChildRole;
import com.intellij.java.language.impl.psi.impl.source.tree.ElementType;
import com.intellij.java.language.impl.psi.impl.source.tree.JavaElementType;
import com.intellij.java.language.psi.PsiKeyword;
import com.intellij.java.language.psi.PsiModifier;
import com.intellij.java.language.psi.PsiModifierList;
import consulo.language.ast.ASTNode;
import consulo.language.ast.ChildRoleBase;
import consulo.language.impl.ast.CompositeElement;
import consulo.language.impl.ast.TreeElement;
import consulo.language.impl.psi.SourceTreeToPsiMap;
import consulo.logging.Logger;

import jakarta.annotation.Nullable;
import java.util.HashMap;

public class ModifierListElement extends CompositeElement {
  private static final Logger LOG = Logger.getInstance(ModifierListElement.class);

  public ModifierListElement() {
    super(JavaElementType.MODIFIER_LIST);
  }

  @Override
  public TreeElement addInternal(TreeElement first, ASTNode last, ASTNode anchor, Boolean before) {
    if (before == null) {
      if (first == last && ElementType.KEYWORD_BIT_SET.contains(first.getElementType())) {
        anchor = getDefaultAnchor((PsiModifierList)SourceTreeToPsiMap.treeElementToPsi(this),
                                  (PsiKeyword)SourceTreeToPsiMap.treeElementToPsi(first));
        before = Boolean.TRUE;
      }
    }
    return super.addInternal(first, last, anchor, before);
  }

  @Override
  public int getChildRole(ASTNode child) {
    LOG.assertTrue(child.getTreeParent() == this);
    if (child.getElementType() == JavaElementType.ANNOTATION) return ChildRole.ANNOTATION;
    return ChildRoleBase.NONE;
  }

  private static final HashMap<String, Integer> ourModifierToOrderMap = new HashMap<String, Integer>();

  static { //TODO : options?
    ourModifierToOrderMap.put(PsiModifier.PUBLIC, 1);
    ourModifierToOrderMap.put(PsiModifier.PRIVATE, 1);
    ourModifierToOrderMap.put(PsiModifier.PROTECTED, 1);
    ourModifierToOrderMap.put(PsiModifier.STATIC, 2);
    ourModifierToOrderMap.put(PsiModifier.ABSTRACT, 2);
    ourModifierToOrderMap.put(PsiModifier.FINAL, 3);
    ourModifierToOrderMap.put(PsiModifier.SYNCHRONIZED, 4);
    ourModifierToOrderMap.put(PsiModifier.TRANSIENT, 4);
    ourModifierToOrderMap.put(PsiModifier.VOLATILE, 4);
    ourModifierToOrderMap.put(PsiModifier.NATIVE, 5);
    ourModifierToOrderMap.put(PsiModifier.STRICTFP, 6);
  }

  @Nullable
  private static ASTNode getDefaultAnchor(PsiModifierList modifierList, PsiKeyword modifier) {
    Integer order = ourModifierToOrderMap.get(modifier.getText());
    if (order == null) return null;
    for (ASTNode child = SourceTreeToPsiMap.psiToTreeNotNull(modifierList).getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (ElementType.KEYWORD_BIT_SET.contains(child.getElementType())) {
        Integer order1 = ourModifierToOrderMap.get(child.getText());
        if (order1 == null) continue;
        if (order1.intValue() > order.intValue()) {
          return child;
        }
      }
    }
    return null;
  }
}
