/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.java.language.impl.psi.impl.compiled;

import com.intellij.java.language.JavaCoreBundle;
import com.intellij.java.language.JavaLanguage;
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleSettingsFacade;
import consulo.document.util.TextRange;
import consulo.language.Language;
import consulo.language.ast.ASTNode;
import consulo.language.ast.IElementType;
import consulo.language.impl.ast.TreeElement;
import consulo.language.impl.psi.PsiElementBase;
import consulo.language.impl.psi.SourceTreeToPsiMap;
import consulo.language.impl.psi.pointer.Identikit;
import consulo.language.psi.*;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.dataholder.Key;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.VirtualFile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public abstract class ClsElementImpl extends PsiElementBase implements PsiCompiledElement {
  public static final Key<PsiCompiledElement> COMPILED_ELEMENT = Key.create("COMPILED_ELEMENT");

  private static final Logger LOG = Logger.getInstance(ClsElementImpl.class);

  private volatile Pair<TextRange, Identikit> myMirror;

  @Override
  @Nonnull
  public Language getLanguage() {
    return JavaLanguage.INSTANCE;
  }

  @Override
  public PsiManager getManager() {
    return getParent().getManager();
  }

  @Override
  public PsiFile getContainingFile() {
    PsiElement parent = getParent();
    if (parent == null) {
      throw new PsiInvalidElementAccessException(this);
    }
    return parent.getContainingFile();
  }

  @Override
  public final boolean isWritable() {
    return false;
  }

  @Override
  public boolean isPhysical() {
    return true;
  }

  @Override
  public boolean isValid() {
    PsiElement parent = getParent();
    return parent != null && parent.isValid();
  }

  @Override
  public PsiElement copy() {
    return this;
  }

  @Nonnull
  protected PsiElement[] getChildren(@Nullable PsiElement... children) {
    if (children == null) {
      return PsiElement.EMPTY_ARRAY;
    }

    List<PsiElement> list = ContainerUtil.newArrayListWithCapacity(children.length);
    for (PsiElement child : children) {
      if (child != null) {
        list.add(child);
      }
    }
    return PsiUtilCore.toPsiElementArray(list);
  }

  @Override
  public void checkAdd(@Nonnull PsiElement element) throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Nonnull
  static IncorrectOperationException cannotModifyException(@Nonnull PsiCompiledElement element) {
    VirtualFile virtualFile = PsiUtilCore.getVirtualFile(element);
    String path = virtualFile == null ? "?" : virtualFile.getPresentableUrl();
    return new IncorrectOperationException(JavaCoreBundle.message("psi.error.attempt.to.edit.class.file", path));
  }

  @Override
  public PsiElement add(@Nonnull PsiElement element) throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Override
  public PsiElement addBefore(@Nonnull PsiElement element, PsiElement anchor) throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Override
  public PsiElement addAfter(@Nonnull PsiElement element, PsiElement anchor) throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Override
  public void delete() throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Override
  public void checkDelete() throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Override
  public PsiElement replace(@Nonnull PsiElement newElement) throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  public abstract void appendMirrorText(int indentLevel, @Nonnull StringBuilder buffer);

  protected int getIndentSize() {
    return JavaCodeStyleSettingsFacade.getInstance(getProject()).getIndentSize();
  }

  public abstract void setMirror(@Nonnull TreeElement element) throws InvalidMirrorException;

  @Override
  public PsiElement getMirror() {
    PsiFile mirrorFile = ((ClsFileImpl) getContainingFile()).getMirror().getContainingFile();
    Pair<TextRange, Identikit> mirror = myMirror;
    return mirror == null ? null : mirror.second.findPsiElement(mirrorFile, mirror.first.getStartOffset(), mirror.first.getEndOffset());
  }

  @Override
  public final TextRange getTextRange() {
    PsiElement mirror = getMirror();
    return mirror != null ? mirror.getTextRange() : TextRange.EMPTY_RANGE;
  }

  @Override
  public final int getStartOffsetInParent() {
    PsiElement mirror = getMirror();
    return mirror != null ? mirror.getStartOffsetInParent() : -1;
  }

  @Override
  public int getTextLength() {
    String text = getText();
    return text == null ? 0 : text.length();
  }

  @Override
  public PsiElement findElementAt(int offset) {
    PsiElement mirror = getMirror();
    return mirror != null ? mirror.findElementAt(offset) : null;
  }

  @Override
  public PsiReference findReferenceAt(int offset) {
    PsiElement mirror = getMirror();
    return mirror != null ? mirror.findReferenceAt(offset) : null;
  }

  @Override
  public final int getTextOffset() {
    PsiElement mirror = getMirror();
    return mirror != null ? mirror.getTextOffset() : -1;
  }

  @Override
  public String getText() {
    PsiElement mirror = getMirror();
    if (mirror != null) {
      return mirror.getText();
    }

    StringBuilder buffer = new StringBuilder();
    appendMirrorText(0, buffer);
    LOG.warn("Mirror wasn't set for " + this + " in " + getContainingFile() + ", expected text '" + buffer + "'");
    return buffer.toString();
  }

  @Override
  @Nonnull
  public char[] textToCharArray() {
    PsiElement mirror = getMirror();
    return mirror != null ? mirror.textToCharArray() : ArrayUtil.EMPTY_CHAR_ARRAY;
  }

  @Override
  public boolean textMatches(@Nonnull CharSequence text) {
    return getText().equals(text.toString());
  }

  @Override
  public boolean textMatches(@Nonnull PsiElement element) {
    return getText().equals(element.getText());
  }

  @Override
  public ASTNode getNode() {
    return null;
  }

  static void goNextLine(int indentLevel, @Nonnull StringBuilder buffer) {
    buffer.append('\n');
    for (int i = 0; i < indentLevel; i++) {
      buffer.append(' ');
    }
  }

  protected static void appendText(@Nullable PsiElement stub, int indentLevel, @Nonnull StringBuilder buffer) {
    if (stub == null) {
      return;
    }
    ((ClsElementImpl) stub).appendMirrorText(indentLevel, buffer);
  }

  protected static final String NEXT_LINE = "go_to_next_line_and_indent";

  protected static void appendText(@Nullable PsiElement stub, int indentLevel, @Nonnull StringBuilder buffer, @Nonnull String separator) {
    if (stub == null) {
      return;
    }
    int pos = buffer.length();
    ((ClsElementImpl) stub).appendMirrorText(indentLevel, buffer);
    if (buffer.length() != pos) {
      if (separator == NEXT_LINE) {
        goNextLine(indentLevel, buffer);
      } else {
        buffer.append(separator);
      }
    }
  }

  protected void setMirrorCheckingType(@Nonnull TreeElement element, @Nullable IElementType type) throws InvalidMirrorException {
    // uncomment for extended consistency check
    //if (myMirror != null) {
    //  throw new InvalidMirrorException("Mirror should be null: " + myMirror);
    //}

    if (type != null && element.getElementType() != type) {
      throw new InvalidMirrorException(element.getElementType() + " != " + type);
    }

    PsiElement psi = element.getPsi();
    psi.putUserData(COMPILED_ELEMENT, this);
    myMirror = Pair.create(element.getTextRange(), Identikit.fromPsi(psi, JavaLanguage.INSTANCE));
  }

  protected static <T extends PsiElement> void setMirror(@Nullable T stub, @Nullable T mirror) throws InvalidMirrorException {
    if (stub == null || mirror == null) {
      throw new InvalidMirrorException(stub, mirror);
    }
    ((ClsElementImpl) stub).setMirror(SourceTreeToPsiMap.psiToTreeNotNull(mirror));
  }

  protected static <T extends PsiElement> void setMirrorIfPresent(@Nullable T stub, @Nullable T mirror) throws InvalidMirrorException {
    if ((stub == null) != (mirror == null)) {
      throw new InvalidMirrorException(stub, mirror);
    } else if (stub != null) {
      ((ClsElementImpl) stub).setMirror(SourceTreeToPsiMap.psiToTreeNotNull(mirror));
    }
  }

  protected static <T extends PsiElement> void setMirrors(@Nonnull T[] stubs, @Nonnull T[] mirrors) throws InvalidMirrorException {
    setMirrors(Arrays.asList(stubs), Arrays.asList(mirrors));
  }

  protected static <T extends PsiElement> void setMirrors(@Nonnull List<T> stubs, @Nonnull List<T> mirrors) throws InvalidMirrorException {
    if (stubs.size() != mirrors.size()) {
      throw new InvalidMirrorException(stubs, mirrors);
    }
    for (int i = 0; i < stubs.size(); i++) {
      setMirror(stubs.get(i), mirrors.get(i));
    }
  }

  protected static class InvalidMirrorException extends RuntimeException {
    public InvalidMirrorException(@Nonnull String message) {
      super(message);
    }

    public InvalidMirrorException(@Nullable PsiElement stubElement, @Nullable PsiElement mirrorElement) {
      this("stub:" + stubElement + "; mirror:" + mirrorElement);
    }

    public InvalidMirrorException(@Nonnull List<? extends PsiElement> stubElements, @Nonnull List<? extends PsiElement> mirrorElements) {
      this("stub:" + stubElements + "; mirror:" + mirrorElements);
    }
  }
}