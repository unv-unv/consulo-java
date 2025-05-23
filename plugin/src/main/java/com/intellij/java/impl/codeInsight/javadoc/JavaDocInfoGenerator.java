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
package com.intellij.java.impl.codeInsight.javadoc;

import com.intellij.java.impl.javadoc.JavadocGeneratorRunProfile;
import com.intellij.java.impl.lang.java.JavaDocumentationProvider;
import com.intellij.java.language.JavadocBundle;
import com.intellij.java.language.codeInsight.AnnotationUtil;
import com.intellij.java.language.codeInsight.ExternalAnnotationsManager;
import com.intellij.java.language.codeInsight.InferredAnnotationsManager;
import com.intellij.java.language.impl.codeInsight.javadoc.ColorUtil;
import com.intellij.java.language.impl.codeInsight.javadoc.JavaDocUtil;
import com.intellij.java.language.impl.psi.impl.JavaConstantExpressionEvaluator;
import com.intellij.java.language.impl.psi.impl.source.tree.JavaDocElementType;
import com.intellij.java.language.impl.psi.impl.source.tree.JavaElementType;
import com.intellij.java.language.projectRoots.JavaSdkVersion;
import com.intellij.java.language.psi.PsiElementFactory;
import com.intellij.java.language.psi.*;
import com.intellij.java.language.psi.javadoc.*;
import com.intellij.java.language.psi.util.PsiFormatUtil;
import com.intellij.java.language.psi.util.PsiFormatUtilBase;
import consulo.annotation.access.RequiredReadAction;
import consulo.content.bundle.Sdk;
import consulo.ide.impl.idea.openapi.util.io.FileUtil;
import consulo.ide.impl.idea.openapi.vfs.VfsUtilCore;
import consulo.ide.impl.idea.util.ArrayUtilRt;
import consulo.java.language.bundle.JavaSdkTypeUtil;
import consulo.language.LangBundle;
import consulo.language.ast.ASTNode;
import consulo.language.editor.documentation.DocumentationManagerProtocol;
import consulo.language.editor.documentation.DocumentationManagerUtil;
import consulo.language.editor.localize.CodeInsightLocalize;
import consulo.language.psi.*;
import consulo.language.psi.scope.EverythingGlobalScope;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.logging.Logger;
import consulo.module.content.ProjectFileIndex;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import consulo.util.jdom.JDOMUtil;
import consulo.util.lang.*;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaDocInfoGenerator {
  private static final Logger LOG = Logger.getInstance(JavaDocInfoGenerator.class);

  private interface InheritDocProvider<T> {
    Pair<T, InheritDocProvider<T>> getInheritDoc();

    PsiClass getElement();
  }

  private interface DocTagLocator<T> {
    T find(PsiDocCommentOwner owner, PsiDocComment comment);
  }

  private static final String THROWS_KEYWORD = "throws";
  private static final String BR_TAG = "<br>";
  private static final String LINK_TAG = "link";
  private static final String LITERAL_TAG = "literal";
  private static final String CODE_TAG = "code";
  private static final String LINKPLAIN_TAG = "linkplain";
  private static final String INHERIT_DOC_TAG = "inheritDoc";
  private static final String DOC_ROOT_TAG = "docRoot";
  private static final String VALUE_TAG = "value";
  private static final String LT = "&lt;";
  private static final String GT = "&gt;";

  private static final Pattern ourWhitespaces = Pattern.compile("[ \\n\\r\\t]+");
  private static final Pattern ourRelativeHtmlLinks = Pattern.compile("<A.*?HREF=\"([^\":]*)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final InheritDocProvider<PsiDocTag> ourEmptyProvider = new InheritDocProvider<>() {
    @Override
    public Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> getInheritDoc() {
      return null;
    }

    @Override
    public PsiClass getElement() {
      return null;
    }
  };

  private static final InheritDocProvider<PsiElement[]> ourEmptyElementsProvider = mapProvider(ourEmptyProvider, false);

  private final Project myProject;
  private final PsiElement myElement;
  private final JavaSdkVersion mySdkVersion;

  public JavaDocInfoGenerator(Project project, PsiElement element) {
    myProject = project;
    myElement = element;

    Sdk jdk = JavadocGeneratorRunProfile.getSdk(myProject);
    mySdkVersion = jdk == null ? null : JavaSdkTypeUtil.getVersion(jdk);
  }

  private static InheritDocProvider<PsiElement[]> mapProvider(InheritDocProvider<PsiDocTag> i, boolean dropFirst) {
    return new InheritDocProvider<>() {
      @Override
      public Pair<PsiElement[], InheritDocProvider<PsiElement[]>> getInheritDoc() {
        Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> pair = i.getInheritDoc();
        if (pair == null) {
          return null;
        }

        PsiElement[] elements;
        PsiElement[] rawElements = pair.first.getDataElements();
        if (dropFirst && rawElements != null && rawElements.length > 0) {
          elements = new PsiElement[rawElements.length - 1];
          System.arraycopy(rawElements, 1, elements, 0, elements.length);
        } else {
          elements = rawElements;
        }

        return Pair.create(elements, mapProvider(pair.second, dropFirst));
      }

      @Override
      public PsiClass getElement() {
        return i.getElement();
      }
    };
  }

  @RequiredReadAction
  private static DocTagLocator<PsiDocTag> parameterLocator(final int parameterIndex) {
    return (owner, comment) -> {
      if (parameterIndex < 0 || comment == null || !(owner instanceof PsiMethod)) {
        return null;
      }

      PsiParameter[] parameters = ((PsiMethod) owner).getParameterList().getParameters();
      if (parameterIndex >= parameters.length) {
        return null;
      }

      String name = parameters[parameterIndex].getName();
      return getParamTagByName(comment, name);
    };
  }

  private static DocTagLocator<PsiDocTag> typeParameterLocator(final int parameterIndex) {
    return (owner, comment) -> {
      if (parameterIndex < 0 || comment == null || !(owner instanceof PsiTypeParameterListOwner)) {
        return null;
      }

      PsiTypeParameter[] parameters = ((PsiTypeParameterListOwner) owner).getTypeParameters();
      if (parameterIndex >= parameters.length) {
        return null;
      }

      String rawName = parameters[parameterIndex].getName();
      if (rawName == null) {
        return null;
      }
      String name = "<" + rawName + ">";
      return getParamTagByName(comment, name);
    };
  }

  @RequiredReadAction
  private static PsiDocTag getParamTagByName(@Nonnull PsiDocComment comment, String name) {
    PsiDocTag[] tags = comment.findTagsByName("param");
    return getTagByName(tags, name);
  }

  @RequiredReadAction
  private static PsiDocTag getTagByName(@Nonnull PsiDocTag[] tags, String name) {
    for (PsiDocTag tag : tags) {
      PsiDocTagValue value = tag.getValueElement();
      if (value != null) {
        String text = value.getText();
        if (text != null && text.equals(name)) {
          return tag;
        }
      }
    }

    return null;
  }

  private static DocTagLocator<PsiDocTag> exceptionLocator(String name) {
    return (owner, comment) -> {
      if (comment == null) {
        return null;
      }

      for (PsiDocTag tag : getThrowsTags(comment)) {
        PsiDocTagValue value = tag.getValueElement();
        if (value != null) {
          String text = value.getText();
          if (text != null && areWeakEqual(text, name)) {
            return tag;
          }
        }
      }

      return null;
    };
  }

  @Nullable
  public String generateFileInfo() {
    StringBuilder buffer = new StringBuilder();
    if (myElement instanceof PsiFile file) {
      generateFileJavaDoc(buffer, file, true); //used for Ctrl-Click
    }

    return fixupDoc(buffer);
  }

  @Nullable
  private String fixupDoc(@Nonnull final StringBuilder buffer) {
    String text = buffer.toString();
    if (text.isEmpty()) {
      return null;
    }

    text = convertHtmlLinks(text);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Generated JavaDoc:");
      LOG.debug(text);
    }

    text = StringUtil.replaceIgnoreCase(text, "<p/>", "<p></p>");
    return StringUtil.replace(text, "/>", ">");
  }

  private String convertHtmlLinks(String text) {
    if (myElement == null) {
      return text; // we are resolving links in a context, without context, don't change links
    }
    StringBuilder result = new StringBuilder();
    int prev = 0;
    Matcher matcher = ourRelativeHtmlLinks.matcher(text);
    while (matcher.find()) {
      int groupStart = matcher.start(1);
      int groupEnd = matcher.end(1);
      result.append(text, prev, groupStart);
      result.append(convertReference(text.substring(groupStart, groupEnd)));
      prev = groupEnd;
    }
    if (result.length() == 0) {
      return text; // don't copy text over, if there are no matches
    }
    result.append(text, prev, text.length());
    return result.toString();
  }

  protected String convertReference(String href) {
    return ObjectUtil.notNull(createReferenceForRelativeLink(href, myElement), href);
  }

  /**
   * Converts a relative link into {@link DocumentationManagerProtocol#PSI_ELEMENT_PROTOCOL PSI_ELEMENT_PROTOCOL}-type link if possible
   */
  @Nullable
  static String createReferenceForRelativeLink(@Nonnull String relativeLink, @Nonnull PsiElement contextElement) {
    String fragment = null;
    int hashPosition = relativeLink.indexOf('#');
    if (hashPosition >= 0) {
      fragment = relativeLink.substring(hashPosition + 1);
      relativeLink = relativeLink.substring(0, hashPosition);
    }
    PsiElement targetElement;
    if (relativeLink.isEmpty()) {
      targetElement = (contextElement instanceof PsiField || contextElement instanceof PsiMethod)
        ? ((PsiMember) contextElement).getContainingClass() : contextElement;
    } else {
      if (!relativeLink.toLowerCase(Locale.US).endsWith(".htm") && !relativeLink.toLowerCase(Locale.US).endsWith(".html")) {
        return null;
      }
      relativeLink = relativeLink.substring(0, relativeLink.lastIndexOf('.'));

      String packageName = getPackageName(contextElement);
      if (packageName == null) {
        return null;
      }

      Couple<String> pathWithPackage = removeParentReferences(Couple.of(relativeLink, packageName));
      if (pathWithPackage == null) {
        return null;
      }
      relativeLink = pathWithPackage.first;
      packageName = pathWithPackage.second;

      relativeLink = relativeLink.replace('/', '.');

      String qualifiedTargetName = packageName.isEmpty() ? relativeLink : packageName + "." + relativeLink;
      JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(contextElement.getProject());
      targetElement = "package-summary".equals(StringUtil.getShortName(qualifiedTargetName)) ? javaPsiFacade.findPackage(StringUtil.getPackageName(qualifiedTargetName)) : javaPsiFacade
          .findClass(qualifiedTargetName, contextElement.getResolveScope());
    }
    if (targetElement == null) {
      return null;
    }

    if (fragment != null && targetElement instanceof PsiClass targetClass) {
      if (fragment.contains("-") || fragment.contains("(")) {
        for (PsiMethod method : targetClass.getMethods()) {
          Set<String> signatures = JavaDocumentationProvider.getHtmlMethodSignatures(method, true);
          if (signatures.contains(fragment)) {
            targetElement = method;
            fragment = null;
            break;
          }
        }
      } else {
        for (PsiField field : ((PsiClass) targetElement).getFields()) {
          if (fragment.equals(field.getName())) {
            targetElement = field;
            fragment = null;
            break;
          }
        }
      }
    }
    return DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + JavaDocUtil.getReferenceText(targetElement.getProject(), targetElement) + (fragment == null ? "" : DocumentationManagerProtocol
        .PSI_ELEMENT_PROTOCOL_REF_SEPARATOR + fragment);
  }

  /**
   * Takes a pair of strings representing a relative path and a package name, and returns corresponding pair, where path is stripped of
   * leading ../ elements, and package name adjusted correspondingly. Returns {@code null} if there are more ../ elements than package
   * components.
   */
  @Nullable
  static Couple<String> removeParentReferences(Couple<String> pathWithContextPackage) {
    String path = pathWithContextPackage.first;
    String packageName = pathWithContextPackage.second;
    while (path.startsWith("../")) {
      if (packageName.isEmpty()) {
        return null;
      }
      int dotPos = packageName.lastIndexOf('.');
      packageName = dotPos < 0 ? "" : packageName.substring(0, dotPos);
      path = path.substring(3);
    }
    return Couple.of(path, packageName);
  }

  static String getPackageName(PsiElement element) {
    String packageName = null;
    if (element instanceof PsiJavaPackage javaPackage) {
      packageName = javaPackage.getQualifiedName();
    } else {
      PsiFile file = element.getContainingFile();
      if (file instanceof PsiClassOwner classOwner) {
        packageName = classOwner.getPackageName();
      }
    }
    return packageName;
  }

  @RequiredReadAction
  public boolean generateDocInfoCore(StringBuilder buffer, boolean generatePrologueAndEpilogue) {
    if (myElement instanceof PsiClass psiClass) {
      generateClassJavaDoc(buffer, psiClass, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiMethod method) {
      generateMethodJavaDoc(buffer, method, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiParameter parameter) {
      generateMethodParameterJavaDoc(buffer, parameter, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiField field) {
      generateFieldJavaDoc(buffer, field, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiVariable variable) {
      generateVariableJavaDoc(buffer, variable, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiDirectory directory) {
      PsiJavaPackage aPackage = JavaDirectoryService.getInstance().getPackage(directory);
      if (aPackage == null) {
        return false;
      }
      generatePackageJavaDoc(buffer, aPackage, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiJavaPackage javaPackage) {
      generatePackageJavaDoc(buffer, javaPackage, generatePrologueAndEpilogue);
    } else if (myElement instanceof PsiJavaModule javaModule) {
      generateModuleJavaDoc(buffer, javaModule, generatePrologueAndEpilogue);
    } else {
      return false;
    }

    return true;
  }

  @RequiredReadAction
  public static String generateSignature(PsiElement element) {
    StringBuilder buf = new StringBuilder();
    if (element instanceof PsiClass psiClass) {
      if (generateClassSignature(buf, psiClass, false)) {
        return null;
      }
    } else if (element instanceof PsiField field) {
      generateFieldSignature(buf, field, false);
    } else if (element instanceof PsiMethod method) {
      generateMethodSignature(buf, method, false, true);
    }
    return buf.toString();
  }

  @Nullable
  @RequiredReadAction
  public String generateDocInfo(List<String> docURLs) {
    StringBuilder buffer = new StringBuilder();

    if (!generateDocInfoCore(buffer, true)) {
      return null;
    }

    if (docURLs != null) {
      if (buffer.length() > 0 && elementHasSourceCode()) {
        LOG.debug("Documentation for " + myElement + " was generated from source code, it wasn't found at following URLs: ", docURLs);
      } else {
        if (buffer.length() == 0) {
          buffer.append("<html><body></body></html>");
        }
        String errorSection = "<p id=\"error\">Following external urls were checked:<br>&nbsp;&nbsp;&nbsp;<i>" + StringUtil.join(docURLs, XmlStringUtil::escapeString, "</i><br>&nbsp;&nbsp;" +
            "&nbsp;<i>") + "</i><br>The documentation for this element is not found. Please add all the needed paths to API docs in " + "<a href=\"open://Project Settings\">Project " +
            "Settings.</a></p>";
        buffer.insert(buffer.indexOf("<body>"), errorSection);
      }
    }

    return fixupDoc(buffer);
  }

  private boolean elementHasSourceCode() {
    PsiFileSystemItem[] items;
    if (myElement instanceof PsiDirectory directory) {
      final PsiJavaPackage aPackage = JavaDirectoryService.getInstance().getPackage(directory);
      if (aPackage == null) {
        return false;
      }
      items = aPackage.getDirectories(new EverythingGlobalScope(myProject));
    } else if (myElement instanceof PsiJavaPackage javaPackage) {
      items = javaPackage.getDirectories(new EverythingGlobalScope(myProject));
    } else {
      PsiFile containingFile = myElement.getNavigationElement().getContainingFile();
      if (containingFile == null) {
        return false;
      }
      items = new PsiFileSystemItem[]{containingFile};
    }
    ProjectFileIndex projectFileIndex = ProjectFileIndex.SERVICE.getInstance(myProject);
    for (PsiFileSystemItem item : items) {
      VirtualFile file = item.getVirtualFile();
      if (file != null && projectFileIndex.isInSource(file)) {
        return true;
      }
    }
    return false;
  }

  @RequiredReadAction
  private void generateClassJavaDoc(StringBuilder buffer, PsiClass aClass, boolean generatePrologueAndEpilogue) {
    if (aClass instanceof PsiAnonymousClass) {
      return;
    }

    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    PsiFile file = aClass.getContainingFile();
    if (file instanceof PsiJavaFile javaFile) {
      String packageName = javaFile.getPackageName();
      if (!packageName.isEmpty()) {
        buffer.append("<small><b>");
        buffer.append(packageName);
        buffer.append("</b></small>");
      }
    }

    buffer.append("<PRE>");
    if (generateClassSignature(buffer, aClass, true)) {
      return;
    }
    buffer.append("</PRE>");

    PsiDocComment comment = getDocComment(aClass);
    if (comment != null) {
      generateCommonSection(buffer, comment);
      generateTypeParametersSection(buffer, aClass);
    }

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  @RequiredReadAction
  private static boolean generateClassSignature(StringBuilder buffer, PsiClass aClass, boolean generateLink) {
    generateAnnotations(buffer, aClass, generateLink, true, false);
    String modifiers = PsiFormatUtil.formatModifiers(aClass, PsiFormatUtilBase.JAVADOC_MODIFIERS_ONLY);
    if (!modifiers.isEmpty()) {
      buffer.append(modifiers);
      buffer.append(" ");
    }
    buffer.append(LangBundle.message(aClass.isInterface() ? "java.terms.interface" : "java.terms.class"));
    buffer.append(" ");
    String refText = JavaDocUtil.getReferenceText(aClass.getProject(), aClass);
    if (refText == null) {
      buffer.setLength(0);
      return true;
    }
    String labelText = JavaDocUtil.getLabelText(aClass.getProject(), aClass.getManager(), refText, aClass);
    buffer.append("<b>");
    buffer.append(labelText);
    buffer.append("</b>");

    buffer.append(generateTypeParameters(aClass, false));

    buffer.append("\n");

    PsiClassType[] refs = aClass.getExtendsListTypes();

    String qName = aClass.getQualifiedName();

    if (refs.length > 0 || !aClass.isInterface() && (qName == null || !qName.equals(CommonClassNames.JAVA_LANG_OBJECT))) {
      buffer.append("extends ");
      if (refs.length == 0) {
        generateLink(buffer, CommonClassNames.JAVA_LANG_OBJECT, null, aClass, false);
      } else {
        for (int i = 0; i < refs.length; i++) {
          generateType(buffer, refs[i], aClass, generateLink);
          if (i < refs.length - 1) {
            buffer.append(",&nbsp;");
          }
        }
      }
      buffer.append("\n");
    }

    refs = aClass.getImplementsListTypes();

    if (refs.length > 0) {
      buffer.append("implements ");
      for (int i = 0; i < refs.length; i++) {
        generateType(buffer, refs[i], aClass, generateLink);
        if (i < refs.length - 1) {
          buffer.append(",&nbsp;");
        }
      }
      buffer.append("\n");
    }
    if (buffer.charAt(buffer.length() - 1) == '\n') {
      buffer.setLength(buffer.length() - 1);
    }
    return false;
  }

  @RequiredReadAction
  private void generateTypeParametersSection(final StringBuilder buffer, final PsiClass aClass) {
    final LinkedList<ParamInfo> result = new LinkedList<>();
    final PsiTypeParameter[] typeParameters = aClass.getTypeParameters();
    for (int i = 0; i < typeParameters.length; i++) {
      PsiTypeParameter typeParameter = typeParameters[i];
      String name = "<" + typeParameter.getName() + ">";
      final DocTagLocator<PsiDocTag> locator = typeParameterLocator(i);
      final Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> inClassComment = findInClassComment(aClass, locator);
      if (inClassComment != null) {
        result.add(new ParamInfo(name, inClassComment));
      } else {
        final Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> pair = findInHierarchy(aClass, locator);
        if (pair != null) {
          result.add(new ParamInfo(name, pair));
        }
      }
    }
    generateParametersSection(buffer, CodeInsightLocalize.javadocTypeParameters().get(), result);
  }

  @Nullable
  private static Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> findInHierarchy(PsiClass psiClass, final DocTagLocator<PsiDocTag> locator) {
    for (final PsiClass superClass : psiClass.getSupers()) {
      final Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> pair = findInClassComment(superClass, locator);
      if (pair != null) {
        return pair;
      }
    }
    for (PsiClass superInterface : psiClass.getInterfaces()) {
      final Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> pair = findInClassComment(superInterface, locator);
      if (pair != null) {
        return pair;
      }
    }
    return null;
  }

  private static Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> findInClassComment(final PsiClass psiClass, final DocTagLocator<PsiDocTag> locator) {
    final PsiDocTag tag = locator.find(psiClass, getDocComment(psiClass));
    if (tag != null) {
      return new Pair<>(tag, new InheritDocProvider<PsiDocTag>() {
        @Override
        public Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> getInheritDoc() {
          return findInHierarchy(psiClass, locator);
        }

        @Override
        public PsiClass getElement() {
          return psiClass;
        }
      });
    }
    return null;
  }

  @Nullable
  private static PsiDocComment getDocComment(final PsiDocCommentOwner docOwner) {
    PsiElement navElement = docOwner.getNavigationElement();
    if (!(navElement instanceof PsiDocCommentOwner)) {
      LOG.info("Wrong navElement: " + navElement + "; original = " + docOwner + " of class " + docOwner.getClass());
      return null;
    }
    PsiDocComment comment = ((PsiDocCommentOwner) navElement).getDocComment();
    if (comment == null) { //check for non-normalized fields
      final PsiModifierList modifierList = docOwner.getModifierList();
      if (modifierList != null) {
        final PsiElement parent = modifierList.getParent();
        if (parent instanceof PsiDocCommentOwner && parent.getNavigationElement() instanceof PsiDocCommentOwner docCommentOwner) {
          return docCommentOwner.getDocComment();
        }
      }
    }
    return comment;
  }

  @RequiredReadAction
  private void generateFieldJavaDoc(StringBuilder buffer, PsiField field, boolean generatePrologueAndEpilogue) {
    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    generateLinkToParentIfNeeded(buffer, field);

    buffer.append("<PRE>");
    generateFieldSignature(buffer, field, true);
    buffer.append("</PRE>");

    ColorUtil.appendColorPreview(field, buffer);

    PsiDocComment comment = getDocComment(field);
    if (comment != null) {
      generateCommonSection(buffer, comment);
    }

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  @RequiredReadAction
  private static void generateFieldSignature(StringBuilder buffer, PsiField field, boolean generateLink) {
    generateAnnotations(buffer, field, generateLink, true, false);
    String modifiers = PsiFormatUtil.formatModifiers(field, PsiFormatUtilBase.JAVADOC_MODIFIERS_ONLY);
    if (!modifiers.isEmpty()) {
      buffer.append(modifiers);
      buffer.append(" ");
    }
    generateType(buffer, field.getType(), field, generateLink);
    buffer.append(" ");
    buffer.append("<b>");
    buffer.append(field.getName());
    appendInitializer(buffer, field);
    enumConstantOrdinal(buffer, field, field.getContainingClass(), "\n");
    buffer.append("</b>");
  }

  public static void enumConstantOrdinal(StringBuilder buffer, PsiField field, PsiClass parentClass, final String newLine) {
    if (parentClass != null && field instanceof PsiEnumConstant) {
      final PsiField[] fields = parentClass.getFields();
      final int idx = ArrayUtilRt.find(fields, field);
      if (idx >= 0) {
        buffer.append(newLine);
        buffer.append("Enum constant ordinal: ").append(idx);
      }
    }
  }

  // not a javadoc in fact..
  @RequiredReadAction
  private void generateVariableJavaDoc(StringBuilder buffer, PsiVariable variable, boolean generatePrologueAndEpilogue) {
    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    buffer.append("<PRE>");
    String modifiers = PsiFormatUtil.formatModifiers(variable, PsiFormatUtilBase.JAVADOC_MODIFIERS_ONLY);
    if (!modifiers.isEmpty()) {
      buffer.append(modifiers);
      buffer.append(" ");
    }
    generateType(buffer, variable.getType(), variable);
    buffer.append(" ");
    buffer.append("<b>");
    buffer.append(variable.getName());
    appendInitializer(buffer, variable);
    buffer.append("</b>");
    buffer.append("</PRE>");

    ColorUtil.appendColorPreview(variable, buffer);

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  // not a javadoc in fact..
  private void generateFileJavaDoc(StringBuilder buffer, PsiFile file, boolean generatePrologueAndEpilogue) {
    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile != null) {
      buffer.append(virtualFile.getPresentableUrl());
    }

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  @RequiredReadAction
  private void generatePackageJavaDoc(final StringBuilder buffer, final PsiJavaPackage PsiJavaPackage, boolean generatePrologueAndEpilogue) {
    for (PsiDirectory directory : PsiJavaPackage.getDirectories(new EverythingGlobalScope(myProject))) {
      final PsiFile packageInfoFile = directory.findFile(PsiJavaPackage.PACKAGE_INFO_FILE);
      if (packageInfoFile != null) {
        final ASTNode node = packageInfoFile.getNode();
        if (node != null) {
          final ASTNode docCommentNode = findRelevantCommentNode(node);
          if (docCommentNode != null) {
            if (generatePrologueAndEpilogue) {
              generatePrologue(buffer);
            }
            generateCommonSection(buffer, (PsiDocComment) docCommentNode.getPsi());
            if (generatePrologueAndEpilogue) {
              generateEpilogue(buffer);
            }
            break;
          }
        }
      }
      PsiFile packageHtmlFile = directory.findFile("package.html");
      if (packageHtmlFile != null) {
        generatePackageHtmlJavaDoc(buffer, packageHtmlFile, generatePrologueAndEpilogue);
        break;
      }
    }
  }

  @RequiredReadAction
  private void generateModuleJavaDoc(StringBuilder buffer, PsiJavaModule module, boolean generatePrologueAndEpilogue) {
    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    buffer.append("<pre>module <b>").append(module.getName()).append("</b></pre>");

    PsiDocComment comment = module.getDocComment();
    if (comment != null) {
      generateCommonSection(buffer, comment);
    }

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  /**
   * Finds doc comment immediately preceding package statement
   */
  @Nullable
  private static ASTNode findRelevantCommentNode(@Nonnull ASTNode fileNode) {
    ASTNode node = fileNode.findChildByType(JavaElementType.PACKAGE_STATEMENT);
    if (node == null) {
      node = fileNode.getLastChildNode();
    }
    while (node != null && node.getElementType() != JavaDocElementType.DOC_COMMENT) {
      node = node.getTreePrev();
    }
    return node;
  }

  @RequiredReadAction
  public void generateCommonSection(StringBuilder buffer, PsiDocComment docComment) {
    generateDescription(buffer, docComment);
    generateApiSection(buffer, docComment);
    generateDeprecatedSection(buffer, docComment);
    generateSinceSection(buffer, docComment);
    generateSeeAlsoSection(buffer, docComment);
  }

  @RequiredReadAction
  private void generateApiSection(StringBuilder buffer, PsiDocComment comment) {
    final String[] tagNames = {
        "apiNote",
        "implSpec",
        "implNote"
    };
    for (String tagName : tagNames) {
      PsiDocTag tag = comment.findTagByName(tagName);
      if (tag != null) {
        buffer.append("<DD><DL>");
        buffer.append("<DT><b>").append(tagName).append("</b>");
        buffer.append("<DD>");
        generateValue(buffer, tag.getDataElements(), ourEmptyElementsProvider);
        buffer.append("</DD></DL></DD>");
      }
    }
  }

  @RequiredReadAction
  private void generatePackageHtmlJavaDoc(final StringBuilder buffer, final PsiFile packageHtmlFile, boolean generatePrologueAndEpilogue) {
    String htmlText = packageHtmlFile.getText();

    try {
      final Document document = JDOMUtil.loadDocument(new ByteArrayInputStream(htmlText.getBytes(StandardCharsets.UTF_8)));
      final Element rootTag = document.getRootElement();
      final Element subTag = rootTag.getChild("body");
      if (subTag != null) {
        htmlText = subTag.getValue();
      }
    } catch (JDOMException | IOException ignore) {
    }

    htmlText = StringUtil.replace(htmlText, "*/", "&#42;&#47;");

    final String fileText = "/** " + htmlText + " */";
    final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(packageHtmlFile.getProject()).getElementFactory();
    final PsiDocComment docComment;
    try {
      docComment = elementFactory.createDocCommentFromText(fileText);
    } catch (IncorrectOperationException e) {
      LOG.error(e);
      return;
    }

    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }
    generateCommonSection(buffer, docComment);
    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  public static
  @Nullable
  PsiExpression calcInitializerExpression(PsiVariable variable) {
    PsiExpression initializer = variable.getInitializer();
    if (initializer != null) {
      PsiModifierList modifierList = variable.getModifierList();
      if (modifierList != null && modifierList.hasModifierProperty(PsiModifier.FINAL) && !(initializer instanceof PsiLiteralExpression)) {
        JavaPsiFacade instance = JavaPsiFacade.getInstance(variable.getProject());
        Object o = instance.getConstantEvaluationHelper().computeConstantExpression(initializer);
        if (o != null) {
          String text = o.toString();
          PsiType type = variable.getType();
          if (type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
            text = "\"" + StringUtil.escapeStringCharacters(StringUtil.shortenPathWithEllipsis(text, 120)) + "\"";
          } else if (type.equalsToText("char")) {
            text = "'" + text + "'";
          }
          try {
            return instance.getElementFactory().createExpressionFromText(text, variable);
          } catch (IncorrectOperationException ex) {
            LOG.info("type:" + type.getCanonicalText() + "; text: " + text, ex);
          }
        }
      }
    }
    return null;
  }

  @RequiredReadAction
  public static boolean appendExpressionValue(StringBuilder buffer, PsiExpression initializer, String label) {
    String text = initializer.getText().trim();
    int index1 = text.indexOf('\n');
    if (index1 < 0) {
      index1 = text.length();
    }
    int index2 = text.indexOf('\r');
    if (index2 < 0) {
      index2 = text.length();
    }
    int index = Math.min(index1, index2);
    boolean trunc = index < text.length();
    text = text.substring(0, index);
    buffer.append(label);
    buffer.append(StringUtil.escapeXml(text));
    if (trunc) {
      buffer.append("...");
    }
    return trunc;
  }

  @RequiredReadAction
  private static void appendInitializer(StringBuilder buffer, PsiVariable variable) {
    PsiExpression initializer = variable.getInitializer();
    if (initializer != null) {
      buffer.append(" = ");

      String text = initializer.getText();
      text = text.trim();
      int index1 = text.indexOf('\n');
      if (index1 < 0) {
        index1 = text.length();
      }
      int index2 = text.indexOf('\r');
      if (index2 < 0) {
        index2 = text.length();
      }
      int index = Math.min(index1, index2);
      boolean trunc = index < text.length();
      if (trunc) {
        text = text.substring(0, index);
        buffer.append(StringUtil.escapeXml(text));
        buffer.append("...");
      } else {
        initializer.accept(new MyVisitor(buffer));
      }
      PsiExpression constantInitializer = calcInitializerExpression(variable);
      if (constantInitializer != null) {
        buffer.append("\n");
        appendExpressionValue(buffer, constantInitializer, JavadocBundle.message("javadoc.resolved.value"));
      }
    }
  }

  @RequiredReadAction
  private static void generateAnnotations(
    @Nonnull StringBuilder buffer,
    @Nonnull PsiModifierListOwner owner,
    boolean generateLink,
    boolean splitAnnotations,
    boolean useShortNames
  ) {
    final PsiModifierList ownerModifierList = owner.getModifierList();
    if (ownerModifierList == null) {
      return;
    }
    generateAnnotations(buffer, owner, ownerModifierList.getAnnotations(), false, generateLink, splitAnnotations, useShortNames);
    PsiAnnotation[] externalAnnotations = ExternalAnnotationsManager.getInstance(owner.getProject()).findExternalAnnotations(owner);
    if (externalAnnotations == null) {
      externalAnnotations = PsiAnnotation.EMPTY_ARRAY;
    }
    PsiAnnotation[] inferredAnnotations = InferredAnnotationsManager.getInstance(owner.getProject()).findInferredAnnotations(owner);
    externalAnnotations = ArrayUtil.mergeArrays(externalAnnotations, inferredAnnotations, PsiAnnotation.ARRAY_FACTORY);
    generateAnnotations(buffer, owner, externalAnnotations, true, generateLink, splitAnnotations, useShortNames);
  }

  @RequiredReadAction
  private static void generateAnnotations(
    StringBuilder buffer,
    PsiModifierListOwner owner,
    PsiAnnotation[] annotations,
    boolean external,
    boolean generateLink,
    boolean splitAnnotations,
    boolean useShortNames
  ) {
    PsiManager manager = owner.getManager();

    Set<String> shownAnnotations = new HashSet<>();

    for (PsiAnnotation annotation : annotations) {
      final PsiJavaCodeReferenceElement nameReferenceElement = annotation.getNameReferenceElement();
      if (nameReferenceElement == null) {
        continue;
      }
      final PsiElement resolved = nameReferenceElement.resolve();
      boolean inferred = AnnotationUtil.isInferredAnnotation(annotation);
      String qualifiedName = annotation.getQualifiedName();
      if (!(shownAnnotations.add(qualifiedName) || isRepeatableAnnotationType(resolved))) {
        continue;
      }

      if (resolved instanceof PsiClass annotationType && qualifiedName != null
        && JavaDocUtil.findReferenceTarget(owner.getManager(), qualifiedName, owner) != null) {
        if (isDocumentedAnnotationType(annotationType)) {
          if (inferred) {
            buffer.append("<i>");
          }
          final PsiClassType type = JavaPsiFacade.getInstance(manager.getProject()).getElementFactory().createType(annotationType, PsiSubstitutor.EMPTY);
          buffer.append("@");
          if (inferred && !generateLink) {
            buffer.append(type.getPresentableText());
          } else {
            generateType(buffer, type, owner, generateLink, useShortNames && !external);
          }
          final PsiNameValuePair[] attributes = annotation.getParameterList().getAttributes();
          if (attributes.length > 0) {
            buffer.append("(");
            boolean first = true;
            for (PsiNameValuePair pair : attributes) {
              if (!first) {
                buffer.append(",&nbsp;");
              }
              first = false;
              final String name = pair.getName();
              if (name != null) {
                buffer.append(name);
                buffer.append(" = ");
              }
              final PsiAnnotationMemberValue value = pair.getValue();
              if (value != null) {
                if (value instanceof PsiArrayInitializerMemberValue) {
                  buffer.append("{");
                  boolean firstMember = true;
                  for (PsiAnnotationMemberValue memberValue : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                    if (!firstMember) {
                      buffer.append(",");
                    }
                    firstMember = false;
                    appendLinkOrText(buffer, memberValue, generateLink);
                  }
                  buffer.append("}");
                } else {
                  appendLinkOrText(buffer, value, generateLink);
                }
              }
            }
            buffer.append(")");
          }
          if (inferred) {
            buffer.append("</i>");
          }
          buffer.append("&nbsp;");
        }
      } else if (external) {
        if (inferred) {
          buffer.append("<i>");
        }
        String annoText = inferred ? "@" + annotation.getNameReferenceElement().getReferenceName() + annotation.getParameterList().getText() : annotation.getText();
        buffer.append(XmlStringUtil.escapeString(annoText));
        if (inferred) {
          buffer.append("</i>");
        }
        buffer.append("&nbsp;");
      } else {
        buffer.append("<font color=red>");
        buffer.append(XmlStringUtil.escapeString(annotation.getText()));
        buffer.append("</font>");
        buffer.append("&nbsp;");
      }
      if (splitAnnotations) {
        buffer.append("\n");
      }
    }
  }

  @RequiredReadAction
  private static void appendLinkOrText(StringBuilder buffer, PsiAnnotationMemberValue memberValue, boolean generateLink) {
    if (generateLink && memberValue instanceof PsiQualifiedReferenceElement qualifiedReferenceElement) {
      String text = qualifiedReferenceElement.getCanonicalText();
      PsiElement resolve = qualifiedReferenceElement.resolve();

      if (resolve instanceof PsiField field) {
        PsiClass aClass = field.getContainingClass();
        int startOfPropertyNamePosition = text.lastIndexOf('.');

        if (startOfPropertyNamePosition != -1) {
          text = text.substring(0, startOfPropertyNamePosition) + '#' + text.substring(startOfPropertyNamePosition + 1);
        } else {
          if (aClass != null) {
            text = aClass.getQualifiedName() + '#' + field.getName();
          }
        }
        generateLink(buffer, text, aClass != null ? aClass.getName() + '.' + field.getName() : null, memberValue, false);
        return;
      }
    }

    buffer.append(XmlStringUtil.escapeString(memberValue.getText()));
  }

  public static boolean isDocumentedAnnotationType(@Nullable PsiElement annotationType) {
    return annotationType instanceof PsiClass psiClass
      && AnnotationUtil.isAnnotated(psiClass, "java.lang.annotation.Documented", false);
  }

  public static boolean isRepeatableAnnotationType(@Nullable PsiElement annotationType) {
    return annotationType instanceof PsiClass psiClass
      && AnnotationUtil.isAnnotated(psiClass, CommonClassNames.JAVA_LANG_ANNOTATION_REPEATABLE, false, true);
  }

  @RequiredReadAction
  private void generateMethodParameterJavaDoc(StringBuilder buffer, PsiParameter parameter, boolean generatePrologueAndEpilogue) {
    String parameterName = parameter.getName();

    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    buffer.append("<PRE>");
    String modifiers = PsiFormatUtil.formatModifiers(parameter, PsiFormatUtilBase.JAVADOC_MODIFIERS_ONLY);
    if (!modifiers.isEmpty()) {
      buffer.append(modifiers);
      buffer.append(" ");
    }
    generateAnnotations(buffer, parameter, true, true, false);
    generateType(buffer, parameter.getType(), parameter);
    buffer.append(" ");
    buffer.append("<b>");
    buffer.append(parameterName);
    appendInitializer(buffer, parameter);
    buffer.append("</b>");
    buffer.append("</PRE>");

    final PsiElement method = PsiTreeUtil.getParentOfType(parameter, PsiMethod.class, PsiLambdaExpression.class);

    if (method instanceof PsiMethod psiMethod) {
      PsiParameterList parameterList = psiMethod.getParameterList();
      if (parameter.getParent() == parameterList) { // this can also be a parameter in foreach statement or in catch clause
        final PsiDocComment docComment = getDocComment(psiMethod);
        final PsiDocTag[] localTags = docComment != null ? docComment.getTags() : PsiDocTag.EMPTY_ARRAY;
        int parameterIndex = parameterList.getParameterIndex(parameter);
        final ParamInfo tagInfoProvider = findDocTag(localTags, parameterName, psiMethod, parameterLocator(parameterIndex));

        if (tagInfoProvider != null) {
          generateOneParameter(buffer, tagInfoProvider);
        }
      }
    }

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  @RequiredReadAction
  private void generateMethodJavaDoc(StringBuilder buffer, PsiMethod method, boolean generatePrologueAndEpilogue) {
    if (generatePrologueAndEpilogue) {
      generatePrologue(buffer);
    }

    generateLinkToParentIfNeeded(buffer, method);

    buffer.append("<PRE>");
    generateMethodSignature(buffer, method, true, false);
    buffer.append("</PRE>");

    PsiDocComment comment = getMethodDocComment(method);

    generateMethodDescription(buffer, method, comment);

    generateSuperMethodsSection(buffer, method, false);
    generateSuperMethodsSection(buffer, method, true);

    if (comment != null) {
      generateDeprecatedSection(buffer, comment);
    }

    generateParametersSection(buffer, method, comment);
    generateTypeParametersSection(buffer, method, comment);
    generateReturnsSection(buffer, method, comment);
    generateThrowsSection(buffer, method, comment);

    if (comment != null) {
      generateApiSection(buffer, comment);
      generateSinceSection(buffer, comment);
      generateSeeAlsoSection(buffer, comment);
    }

    if (generatePrologueAndEpilogue) {
      generateEpilogue(buffer);
    }
  }

  private static void generateLinkToParentIfNeeded(StringBuilder buffer, PsiMember member) {
    PsiClass parentClass = member.getContainingClass();
    if (parentClass != null) {
      String qName = parentClass.getQualifiedName();
      if (qName != null) {
        buffer.append("<small><b>");
        generateLink(buffer, qName, qName, member, false);
        buffer.append("</b></small>");
      }
    }
  }

  @RequiredReadAction
  private static void generateMethodSignature(StringBuilder buffer, PsiMethod method, boolean generateLink, boolean useShortNames) {
    generateAnnotations(buffer, method, generateLink, true, useShortNames);
    String modifiers = PsiFormatUtil.formatModifiers(method, PsiFormatUtilBase.JAVADOC_MODIFIERS_ONLY);
    int indent = 0;
    if (!modifiers.isEmpty()) {
      buffer.append(modifiers);
      buffer.append("&nbsp;");
      indent += modifiers.length() + 1;
    }

    final String typeParamsString = generateTypeParameters(method, useShortNames);
    indent += StringUtil.unescapeXml(StringUtil.stripHtml(typeParamsString, true)).length();
    if (!typeParamsString.isEmpty()) {
      buffer.append(typeParamsString);
      buffer.append("&nbsp;");
      indent++;
    }

    if (method.getReturnType() != null) {
      indent += generateType(buffer, method.getReturnType(), method, generateLink, useShortNames);
      buffer.append("&nbsp;");
      indent++;
    }
    buffer.append("<b>");
    String name = method.getName();
    buffer.append(name);
    buffer.append("</b>");
    indent += name.length();

    buffer.append("(");

    PsiParameter[] parms = method.getParameterList().getParameters();
    for (int i = 0; i < parms.length; i++) {
      PsiParameter parm = parms[i];
      generateAnnotations(buffer, parm, generateLink, false, useShortNames);
      generateType(buffer, parm.getType(), method, generateLink, useShortNames);
      buffer.append("&nbsp;");
      if (parm.getName() != null) {
        buffer.append(parm.getName());
      }
      if (i < parms.length - 1) {
        buffer.append(",\n ");
        buffer.append(StringUtil.repeat(" ", indent));
      }
    }
    buffer.append(")");

    PsiClassType[] refs = method.getThrowsList().getReferencedTypes();
    if (refs.length > 0) {
      buffer.append("\n");
      indent -= THROWS_KEYWORD.length() + 1;
      for (int i = 0; i < indent; i++) {
        buffer.append(" ");
      }
      indent += THROWS_KEYWORD.length() + 1;
      buffer.append(THROWS_KEYWORD);
      buffer.append("&nbsp;");
      for (int i = 0; i < refs.length; i++) {
        generateLink(buffer, useShortNames ? refs[i].getPresentableText() : refs[i].getCanonicalText(), null, method, false);
        if (i < refs.length - 1) {
          buffer.append(",\n");
          for (int j = 0; j < indent; j++) {
            buffer.append(" ");
          }
        }
      }
    }
  }

  @RequiredReadAction
  private PsiDocComment getMethodDocComment(PsiMethod method) {
    PsiClass parentClass = method.getContainingClass();
    if (parentClass != null && parentClass.isEnum()) {
      PsiParameterList parameterList = method.getParameterList();
      if (method.getName().equals("values") && parameterList.getParametersCount() == 0) {
        return loadSyntheticDocComment(method, "/javadoc/EnumValues.java.template");
      }
      if (method.getName().equals("valueOf") && parameterList.getParametersCount() == 1 && parameterList.getParameters()[0].getType().equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
        return loadSyntheticDocComment(method, "/javadoc/EnumValueOf.java.template");
      }
    }
    return getDocComment(method);
  }

  @RequiredReadAction
  private PsiDocComment loadSyntheticDocComment(PsiMethod method, String resourceName) {
    PsiClass containingClass = method.getContainingClass();
    assert containingClass != null : method;
    String containingClassName = containingClass.getName();
    assert containingClassName != null : containingClass;

    try {
      String text;
      try (InputStream commentStream = JavaDocInfoGenerator.class.getResourceAsStream(resourceName)) {
        if (commentStream == null) {
          return null;
        }
        byte[] bytes = FileUtil.loadBytes(commentStream);
        text = new String(bytes, StandardCharsets.UTF_8);
      }
      text = StringUtil.replace(text, "<ClassName>", containingClassName);
      return JavaPsiFacade.getInstance(myProject).getElementFactory().createDocCommentFromText(text);
    } catch (IOException | IncorrectOperationException e) {
      LOG.info(e);
      return null;
    }
  }

  protected void generatePrologue(StringBuilder buffer) {
    URL baseUrl = getBaseUrl();
    buffer.append("<html><head>");
    if (baseUrl != null) {
      buffer.append("<base href=\"").append(baseUrl).append("\">");
    }
    buffer.append("    <style type=\"text/css\">" + "        #error {" + "            background-color: #eeeeee;" + "            margin-bottom: 10px;" + "        }" + "        p {" + "          " +
        "  margin: 5px 0;" + "        }" + "    </style>" + "</head><body>");
  }

  private URL getBaseUrl() {
    if (myElement == null) {
      return null;
    }
    PsiElement element = myElement.getNavigationElement();
    if (element == null) {
      return null;
    }
    PsiFile file = element.getContainingFile();
    if (file == null) {
      return null;
    }
    VirtualFile vFile = file.getVirtualFile();
    if (vFile == null) {
      return null;
    }
    return VfsUtilCore.convertToURL(vFile.getUrl());
  }

  protected void generateEpilogue(StringBuilder buffer) {
    while (true) {
      if (buffer.length() < BR_TAG.length()) {
        break;
      }
      char c = buffer.charAt(buffer.length() - 1);
      if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
        buffer.setLength(buffer.length() - 1);
        continue;
      }
      String tail = buffer.substring(buffer.length() - BR_TAG.length());
      if (tail.equalsIgnoreCase(BR_TAG)) {
        buffer.setLength(buffer.length() - BR_TAG.length());
        continue;
      }
      break;
    }
    buffer.append("</body></html>");
  }

  @RequiredReadAction
  private void generateDescription(StringBuilder buffer, PsiDocComment comment) {
    PsiElement[] elements = comment.getDescriptionElements();
    generateValue(buffer, elements, ourEmptyElementsProvider);
  }

  @RequiredReadAction
  private static boolean isEmptyDescription(PsiDocComment comment) {
    if (comment == null) {
      return true;
    }

    for (PsiElement description : comment.getDescriptionElements()) {
      String text = description.getText();
      if (text != null && !ourWhitespaces.matcher(text).replaceAll("").isEmpty()) {
        return false;
      }
    }

    return true;
  }

  @RequiredReadAction
  private void generateMethodDescription(StringBuilder buffer, PsiMethod method, PsiDocComment comment) {
    final DocTagLocator<PsiElement[]> descriptionLocator =
      (owner, comment1) -> comment1 != null && !isEmptyDescription(comment1) ? comment1.getDescriptionElements() : null;

    if (comment != null && !isEmptyDescription(comment)) {
      generateValue(buffer, comment.getDescriptionElements(), new InheritDocProvider<>() {
        @Override
        public Pair<PsiElement[], InheritDocProvider<PsiElement[]>> getInheritDoc() {
          return findInheritDocTag(method, descriptionLocator);
        }

        @Override
        public PsiClass getElement() {
          return method.getContainingClass();
        }
      });
      return;
    }

    Pair<PsiElement[], InheritDocProvider<PsiElement[]>> pair = findInheritDocTag(method, descriptionLocator);
    if (pair != null) {
      PsiElement[] elements = pair.first;
      if (elements != null) {
        PsiClass aClass = pair.second.getElement();
        buffer.append("<DD><DL>");
        buffer.append("<DT><b>");
        buffer.append(
          aClass.isInterface()
            ? CodeInsightLocalize.javadocDescriptionCopiedFromInterface().get()
            : CodeInsightLocalize.javadocDescriptionCopiedFromClass().get()
        );
        buffer.append("</b>&nbsp;");
        generateLink(buffer, aClass, JavaDocUtil.getShortestClassName(aClass, method), false);
        buffer.append(BR_TAG);
        generateValue(buffer, elements, pair.second);
        buffer.append("</DD></DL></DD>");
      }
    }
  }

  @RequiredReadAction
  private void generateValue(StringBuilder buffer, PsiElement[] elements, InheritDocProvider<PsiElement[]> provider) {
    generateValue(buffer, elements, 0, provider);
  }

  private String getDocRoot() {
    PsiClass aClass;
    if (myElement instanceof PsiClass psiClass) {
      aClass = psiClass;
    } else if (myElement instanceof PsiMember member) {
      aClass = member.getContainingClass();
    } else {
      aClass = PsiTreeUtil.getParentOfType(myElement, PsiClass.class);
    }

    if (aClass != null) {
      String qName = aClass.getQualifiedName();
      if (qName != null) {
        return StringUtil.repeat("../", StringUtil.countChars(qName, '.') + 1);
      }
    }

    return "";
  }

  @RequiredReadAction
  private void generateValue(StringBuilder buffer, PsiElement[] elements, int startIndex, InheritDocProvider<PsiElement[]> provider) {
    int predictOffset = startIndex < elements.length ? elements[startIndex].getTextOffset() + elements[startIndex].getText().length() : 0;
    for (int i = startIndex; i < elements.length; i++) {
      if (elements[i].getTextOffset() > predictOffset) {
        buffer.append(" ");
      }
      predictOffset = elements[i].getTextOffset() + elements[i].getText().length();
      PsiElement element = elements[i];
      if (element instanceof PsiInlineDocTag tag) {
        final String tagName = tag.getName();
        if (tagName.equals(LINK_TAG)) {
          generateLinkValue(tag, buffer, false);
        } else if (tagName.equals(LITERAL_TAG)) {
          generateLiteralValue(buffer, tag);
        } else if (tagName.equals(CODE_TAG)) {
          generateCodeValue(tag, buffer);
        } else if (tagName.equals(LINKPLAIN_TAG)) {
          generateLinkValue(tag, buffer, true);
        } else if (tagName.equals(INHERIT_DOC_TAG)) {
          Pair<PsiElement[], InheritDocProvider<PsiElement[]>> inheritInfo = provider.getInheritDoc();
          if (inheritInfo != null) {
            generateValue(buffer, inheritInfo.first, inheritInfo.second);
          }
        } else if (tagName.equals(DOC_ROOT_TAG)) {
          buffer.append(getDocRoot());
        } else if (tagName.equals(VALUE_TAG)) {
          generateValueValue(tag, buffer, element);
        }
      } else {
        buffer.append(StringUtil.replaceUnicodeEscapeSequences(element.getText()));
      }
    }
  }

  @RequiredReadAction
  private void generateCodeValue(PsiInlineDocTag tag, StringBuilder buffer) {
    buffer.append("<code>");
    generateLiteralValue(buffer, tag);
    buffer.append("</code>");
  }

  @RequiredReadAction
  private void generateLiteralValue(StringBuilder buffer, PsiDocTag tag) {
    StringBuilder tmpBuffer = new StringBuilder();
    for (PsiElement element : tag.getDataElements()) {
      appendPlainText(StringUtil.escapeXml(element.getText()), tmpBuffer);
    }
    if ((mySdkVersion == null || mySdkVersion.isAtLeast(JavaSdkVersion.JDK_1_8)) && isInPre(tag)) {
      buffer.append(tmpBuffer);
    } else {
      buffer.append(StringUtil.trimLeading(tmpBuffer));
    }
  }

  @RequiredReadAction
  private static boolean isInPre(PsiDocTag tag) {
    PsiElement sibling = tag.getPrevSibling();
    while (sibling != null) {
      if (sibling instanceof PsiDocToken) {
        String text = sibling.getText().toLowerCase();
        int pos = text.lastIndexOf("pre>");
        if (pos > 0) {
          switch (text.charAt(pos - 1)) {
            case '<':
              return true;
            case '/':
              return false;
          }
        }
      }
      sibling = sibling.getPrevSibling();
    }
    return false;
  }

  private static void appendPlainText(String text, final StringBuilder buffer) {
    buffer.append(StringUtil.replaceUnicodeEscapeSequences(text));
  }

  @RequiredReadAction
  protected void generateLinkValue(PsiInlineDocTag tag, StringBuilder buffer, boolean plainLink) {
    PsiElement[] tagElements = tag.getDataElements();
    String text = createLinkText(tagElements);
    if (!text.isEmpty()) {
      int index = JavaDocUtil.extractReference(text);
      String refText = text.substring(0, index).trim();
      String label = text.substring(index).trim();
      if (label.isEmpty()) {
        label = null;
      }
      generateLink(buffer, refText, label, tagElements[0], plainLink);
    }
  }

  @RequiredReadAction
  private void generateValueValue(final PsiInlineDocTag tag, final StringBuilder buffer, final PsiElement element) {
    String text = createLinkText(tag.getDataElements());
    PsiField valueField = null;
    if (text.isEmpty()) {
      if (myElement instanceof PsiField field) {
        valueField = field;
      }
    } else {
      if (text.indexOf('#') == -1) {
        text = "#" + text;
      }
      PsiElement target = JavaDocUtil.findReferenceTarget(PsiManager.getInstance(myProject), text, myElement);
      if (target instanceof PsiField field) {
        valueField = field;
      }
    }

    Object value = null;
    if (valueField != null) {
      PsiExpression initializer = valueField.getInitializer();
      value = JavaConstantExpressionEvaluator.computeConstantExpression(initializer, false);
    }

    if (value != null) {
      String valueText = StringUtil.escapeXml(value.toString());
      if (value instanceof String) {
        valueText = '"' + valueText + '"';
      }
      if (valueField.equals(myElement)) {
        buffer.append(valueText); // don't generate link to itself
      } else {
        generateLink(buffer, valueField, valueText, true);
      }
    } else {
      buffer.append(element.getText());
    }
  }

  @RequiredReadAction
  protected String createLinkText(final PsiElement[] tagElements) {
    int predictOffset = tagElements.length > 0 ? tagElements[0].getTextOffset() + tagElements[0].getText().length() : 0;
    StringBuilder buffer = new StringBuilder();
    for (int j = 0; j < tagElements.length; j++) {
      PsiElement tagElement = tagElements[j];

      if (tagElement.getTextOffset() > predictOffset) {
        buffer.append(" ");
      }
      predictOffset = tagElement.getTextOffset() + tagElement.getText().length();

      collectElementText(buffer, tagElement);

      if (j < tagElements.length - 1) {
        buffer.append(" ");
      }
    }
    return buffer.toString().trim();
  }

  protected void collectElementText(final StringBuilder buffer, PsiElement element) {
    element.accept(new PsiRecursiveElementWalkingVisitor() {
      @RequiredReadAction
      @Override
      public void visitElement(PsiElement element) {
        super.visitElement(element);
        if (element instanceof PsiWhiteSpace || element instanceof PsiJavaToken
          || element instanceof PsiDocToken docToken && docToken.getTokenType() != JavaDocTokenType.DOC_COMMENT_LEADING_ASTERISKS) {
          buffer.append(element.getText());
        }
      }
    });
  }

  @RequiredReadAction
  private void generateDeprecatedSection(StringBuilder buffer, PsiDocComment comment) {
    PsiDocTag tag = comment.findTagByName("deprecated");
    if (tag != null) {
      buffer.append("<DD><DL>");
      buffer.append("<B>").append(CodeInsightLocalize.javadocDeprecated()).append("</B>&nbsp;");
      buffer.append("<I>");
      generateValue(buffer, tag.getDataElements(), ourEmptyElementsProvider);
      buffer.append("</I>");
      buffer.append("</DL></DD>");
    }
  }

  @RequiredReadAction
  private void generateSinceSection(StringBuilder buffer, PsiDocComment comment) {
    PsiDocTag tag = comment.findTagByName("since");
    if (tag != null) {
      buffer.append("<DD><DL>");
      buffer.append("<DT><b>").append(CodeInsightLocalize.javadocSince()).append("</b>");
      buffer.append("<DD>");
      generateValue(buffer, tag.getDataElements(), ourEmptyElementsProvider);
      buffer.append("</DD></DL></DD>");
    }
  }

  @RequiredReadAction
  protected void generateSeeAlsoSection(StringBuilder buffer, PsiDocComment comment) {
    PsiDocTag[] tags = comment.findTagsByName("see");
    if (tags.length > 0) {
      buffer.append("<DD><DL>");
      buffer.append("<DT><b>").append(CodeInsightLocalize.javadocSeeAlso()).append("</b>");
      buffer.append("<DD>");
      for (int i = 0; i < tags.length; i++) {
        PsiDocTag tag = tags[i];
        PsiElement[] elements = tag.getDataElements();
        if (elements.length > 0) {
          String text = createLinkText(elements);
          if (text.startsWith("<")) {
            buffer.append(text);
          } else if (text.startsWith("\"")) {
            appendPlainText(text, buffer);
          } else {
            int index = JavaDocUtil.extractReference(text);
            String refText = text.substring(0, index).trim();
            String label = text.substring(index).trim();
            if (label.isEmpty()) {
              label = null;
            }
            generateLink(buffer, refText, label, comment, false);
          }
        }
        if (i < tags.length - 1) {
          buffer.append(",\n");
        }
      }
      buffer.append("</DD></DL></DD>");
    }
  }

  @RequiredReadAction
  private void generateParametersSection(StringBuilder buffer, final PsiMethod method, final PsiDocComment comment) {
    PsiParameter[] params = method.getParameterList().getParameters();
    PsiDocTag[] localTags = comment != null ? comment.findTagsByName("param") : PsiDocTag.EMPTY_ARRAY;

    LinkedList<ParamInfo> collectedTags = new LinkedList<>();

    for (int i = 0; i < params.length; i++) {
      PsiParameter param = params[i];
      String paramName = param.getName();
      DocTagLocator<PsiDocTag> tagLocator = parameterLocator(i);
      ParamInfo parmTag = findDocTag(localTags, paramName, method, tagLocator);
      if (parmTag != null) {
        collectedTags.addLast(parmTag);
      }
    }

    generateParametersSection(buffer, CodeInsightLocalize.javadocParameters().get(), collectedTags);
  }

  @RequiredReadAction
  private void generateTypeParametersSection(final StringBuilder buffer, final PsiMethod method, PsiDocComment comment) {
    final PsiDocTag[] localTags = comment == null ? PsiDocTag.EMPTY_ARRAY : comment.findTagsByName("param");
    final PsiTypeParameter[] typeParameters = method.getTypeParameters();
    final LinkedList<ParamInfo> collectedTags = new LinkedList<>();
    for (int i = 0; i < typeParameters.length; i++) {
      PsiTypeParameter typeParameter = typeParameters[i];
      final String paramName = "<" + typeParameter.getName() + ">";
      DocTagLocator<PsiDocTag> tagLocator = typeParameterLocator(i);
      ParamInfo parmTag = findDocTag(localTags, paramName, method, tagLocator);
      if (parmTag != null) {
        collectedTags.addLast(parmTag);
      }
    }
    generateParametersSection(buffer, CodeInsightLocalize.javadocTypeParameters().get(), collectedTags);
  }

  @RequiredReadAction
  private void generateParametersSection(StringBuilder buffer, String titleMessage, LinkedList<ParamInfo> collectedTags) {
    if (!collectedTags.isEmpty()) {
      buffer.append("<DD><DL>");
      buffer.append("<DT><b>").append(titleMessage).append("</b>");
      for (ParamInfo tag : collectedTags) {
        generateOneParameter(buffer, tag);
      }
      buffer.append("</DD></DL></DD>");
    }
  }

  @Nullable
  @RequiredReadAction
  private ParamInfo findDocTag(PsiDocTag[] localTags, String paramName, PsiMethod method, DocTagLocator<PsiDocTag> tagLocator) {
    PsiDocTag localTag = getTagByName(localTags, paramName);
    if (localTag != null) {
      return new ParamInfo(paramName, localTag, new InheritDocProvider<>() {
        @Override
        public Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> getInheritDoc() {
          return findInheritDocTag(method, tagLocator);
        }

        @Override
        public PsiClass getElement() {
          return method.getContainingClass();
        }
      });
    }
    Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> tag = findInheritDocTag(method, tagLocator);
    return tag == null ? null : new ParamInfo(paramName, tag);
  }

  @RequiredReadAction
  private void generateOneParameter(StringBuilder buffer, ParamInfo tag) {
    PsiElement[] elements = tag.docTag.getDataElements();
    if (elements.length == 0) {
      return;
    }
    String text = elements[0].getText();
    buffer.append("<DD>");
    int spaceIndex = text.indexOf(' ');
    if (spaceIndex < 0) {
      spaceIndex = text.length();
    }
    buffer.append("<code>");
    buffer.append(StringUtil.escapeXml(tag.name));
    buffer.append("</code>");
    buffer.append(" - ");
    buffer.append(text.substring(spaceIndex));
    generateValue(buffer, elements, 1, mapProvider(tag.inheritDocTagProvider, true));
  }

  @RequiredReadAction
  private void generateReturnsSection(StringBuilder buffer, final PsiMethod method, final PsiDocComment comment) {
    PsiDocTag tag = comment == null ? null : comment.findTagByName("return");
    Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> pair = tag == null ? null : new Pair<>(tag, new InheritDocProvider<PsiDocTag>() {
      @Override
      public Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> getInheritDoc() {
        return findInheritDocTag(method, new ReturnTagLocator());
      }

      @Override
      public PsiClass getElement() {
        return method.getContainingClass();
      }
    });

    if (pair == null && myElement instanceof PsiMethod psiMethod) {
      pair = findInheritDocTag(psiMethod, new ReturnTagLocator());
    }

    if (pair != null) {
      buffer.append("<DD><DL>");
      buffer.append("<DT><b>").append(CodeInsightLocalize.javadocReturns()).append("</b>");
      buffer.append("<DD>");
      generateValue(buffer, pair.first.getDataElements(), mapProvider(pair.second, false));
      buffer.append("</DD></DL></DD>");
    }
  }

  private static PsiDocTag[] getThrowsTags(PsiDocComment comment) {
    if (comment == null) {
      return PsiDocTag.EMPTY_ARRAY;
    }
    PsiDocTag[] tags1 = comment.findTagsByName(THROWS_KEYWORD);
    PsiDocTag[] tags2 = comment.findTagsByName("exception");
    return ArrayUtil.mergeArrays(tags1, tags2);
  }

  private static boolean areWeakEqual(String one, String two) {
    return one.equals(two) || one.endsWith("." + two) || two.endsWith("." + one);
  }

  @RequiredReadAction
  private void generateThrowsSection(StringBuilder buffer, PsiMethod method, PsiDocComment comment) {
    PsiDocTag[] localTags = getThrowsTags(comment);
    LinkedList<Pair<PsiDocTag, InheritDocProvider<PsiDocTag>>> collectedTags = new LinkedList<>();
    List<PsiClassType> declaredThrows = new ArrayList<>(Arrays.asList(method.getThrowsList().getReferencedTypes()));

    for (int i = localTags.length - 1; i > -1; i--) {
      PsiDocTagValue valueElement = localTags[i].getValueElement();

      if (valueElement != null) {
        for (Iterator<PsiClassType> iterator = declaredThrows.iterator(); iterator.hasNext(); ) {
          PsiClassType classType = iterator.next();
          if (Comparing.strEqual(valueElement.getText(), classType.getClassName()) || Comparing.strEqual(valueElement.getText(), classType.getCanonicalText())) {
            iterator.remove();
            break;
          }
        }

        Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> tag = findInheritDocTag(method, exceptionLocator(valueElement.getText()));
        collectedTags.addFirst(new Pair<>(localTags[i], new InheritDocProvider<>() {
          @Override
          public Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> getInheritDoc() {
            return tag;
          }

          @Override
          public PsiClass getElement() {
            return method.getContainingClass();
          }
        }));
      }
    }

    for (PsiClassType trouser : declaredThrows) {
      if (trouser != null) {
        String paramName = trouser.getCanonicalText();
        Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> parmTag = null;

        for (PsiDocTag localTag : localTags) {
          PsiDocTagValue value = localTag.getValueElement();
          if (value != null) {
            String tagName = value.getText();
            if (tagName != null && areWeakEqual(tagName, paramName)) {
              parmTag = Pair.create(localTag, ourEmptyProvider);
              break;
            }
          }
        }

        if (parmTag == null) {
          parmTag = findInheritDocTag(method, exceptionLocator(paramName));
        }

        if (parmTag != null) {
          collectedTags.addLast(parmTag);
        } else {
          try {
            PsiDocTag tag = JavaPsiFacade.getInstance(method.getProject()).getElementFactory().createDocTagFromText("@exception " + paramName);
            collectedTags.addLast(Pair.create(tag, ourEmptyProvider));
          } catch (IncorrectOperationException e) {
            LOG.error(e);
          }
        }
      }
    }

    if (!collectedTags.isEmpty()) {
      buffer.append("<DD><DL>");
      buffer.append("<DT><b>").append(CodeInsightLocalize.javadocThrows()).append("</b>");
      for (Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> tag : collectedTags) {
        PsiElement[] elements = tag.first.getDataElements();
        if (elements.length == 0) {
          continue;
        }
        buffer.append("<DD>");
        String text = elements[0].getText();
        int index = JavaDocUtil.extractReference(text);
        String refText = text.substring(0, index).trim();
        generateLink(buffer, refText, null, method, false);
        String rest = text.substring(index);
        if (!rest.isEmpty() || elements.length > 1) {
          buffer.append(" - ");
        }
        buffer.append(rest);
        generateValue(buffer, elements, 1, mapProvider(tag.second, true));
      }
      buffer.append("</DD></DL></DD>");
    }
  }

  @RequiredReadAction
  private static void generateSuperMethodsSection(StringBuilder buffer, PsiMethod method, boolean overrides) {
    PsiClass parentClass = method.getContainingClass();
    if (parentClass == null) {
      return;
    }
    if (parentClass.isInterface() && !overrides) {
      return;
    }
    PsiMethod[] supers = method.findSuperMethods();
    if (supers.length == 0) {
      return;
    }
    boolean headerGenerated = false;
    for (PsiMethod superMethod : supers) {
      boolean isAbstract = superMethod.hasModifierProperty(PsiModifier.ABSTRACT);
      if (overrides) {
        if (parentClass.isInterface() != isAbstract) {
          continue;
        }
      } else {
        if (!isAbstract) {
          continue;
        }
      }
      PsiClass superClass = superMethod.getContainingClass();
      if (superClass == null) {
        continue;
      }
      if (!headerGenerated) {
        buffer.append("<DD><DL>");
        buffer.append("<DT><b>");
        buffer.append(overrides ? CodeInsightLocalize.javadocMethodOverrides() : CodeInsightLocalize.javadocMethodSpecifiedBy());
        buffer.append("</b>");
        headerGenerated = true;
      }
      buffer.append("<DD>");

      StringBuilder methodBuffer = new StringBuilder();
      generateLink(methodBuffer, superMethod, superMethod.getName(), false);
      StringBuilder classBuffer = new StringBuilder();
      generateLink(classBuffer, superClass, superClass.getName(), false);
      if (superClass.isInterface()) {
        buffer.append(CodeInsightLocalize.javadocMethodInInterface(methodBuffer.toString(), classBuffer.toString()));
      } else {
        buffer.append(CodeInsightLocalize.javadocMethodInClass(methodBuffer.toString(), classBuffer.toString()));
      }
    }
    if (headerGenerated) {
      buffer.append("</DD></DL></DD>");
    }
  }

  public static void generateLink(StringBuilder buffer, PsiElement element, String label, boolean plainLink) {
    String refText = JavaDocUtil.getReferenceText(element.getProject(), element);
    if (refText != null) {
      DocumentationManagerUtil.createHyperlink(buffer, element, refText, label, plainLink);
    }
  }

  /**
   * @return Length of the generated label.
   */
  public static int generateLink(StringBuilder buffer, String refText, String label, @Nonnull PsiElement context, boolean plainLink) {
    if (label == null) {
      PsiManager manager = context.getManager();
      label = JavaDocUtil.getLabelText(manager.getProject(), manager, refText, context);
    }
    LOG.assertTrue(refText != null, "refText appears to be null.");
    PsiElement target = JavaDocUtil.findReferenceTarget(context.getManager(), refText, context);
    if (target == null) {
      buffer.append("<font color=red>").append(label).append("</font>");
    } else {
      generateLink(buffer, target, label, plainLink);
    }
    return StringUtil.stripHtml(label, true).length();
  }

  /**
   * @return Length of the generated label.
   */
  public static int generateType(StringBuilder buffer, PsiType type, PsiElement context) {
    return generateType(buffer, type, context, true);
  }

  /**
   * @return Length of the generated label.
   */
  public static int generateType(StringBuilder buffer, PsiType type, PsiElement context, boolean generateLink) {
    return generateType(buffer, type, context, generateLink, false);
  }

  /**
   * @return Length of the generated label.
   */
  public static int generateType(StringBuilder buffer, PsiType type, PsiElement context, boolean generateLink, boolean useShortNames) {
    if (type instanceof PsiPrimitiveType) {
      String text = StringUtil.escapeXml(type.getCanonicalText());
      buffer.append(text);
      return text.length();
    }

    if (type instanceof PsiArrayType arrayType) {
      int rest = generateType(buffer, arrayType.getComponentType(), context, generateLink, useShortNames);
      if (type instanceof PsiEllipsisType) {
        buffer.append("...");
        return rest + 3;
      } else {
        buffer.append("[]");
        return rest + 2;
      }
    }

    if (type instanceof PsiCapturedWildcardType capturedWildcardType) {
      type = capturedWildcardType.getWildcard();
    }

    if (type instanceof PsiWildcardType wt) {
      buffer.append("?");
      PsiType bound = wt.getBound();
      if (bound != null) {
        String keyword = wt.isExtends() ? " extends " : " super ";
        buffer.append(keyword);
        return generateType(buffer, bound, context, generateLink, useShortNames) + 1 + keyword.length();
      } else {
        return 1;
      }
    }

    if (type instanceof PsiClassType classType) {
      PsiClassType.ClassResolveResult result = classType.resolveGenerics();
      PsiClass psiClass = result.getElement();
      PsiSubstitutor psiSubst = result.getSubstitutor();

      if (psiClass == null) {
        String canonicalText = type.getCanonicalText();
        String text = "<font color=red>" + StringUtil.escapeXml(canonicalText) + "</font>";
        buffer.append(text);
        return canonicalText.length();
      }

      String qName = psiClass.getQualifiedName();

      if (qName == null || psiClass instanceof PsiTypeParameter) {
        String text = StringUtil.escapeXml(useShortNames ? type.getPresentableText() : type.getCanonicalText());
        buffer.append(text);
        return text.length();
      }

      String name = useShortNames ? ((PsiClassType) type).rawType().getPresentableText() : qName;

      int length;
      if (generateLink) {
        length = generateLink(buffer, name, null, context, false);
      } else {
        buffer.append(name);
        length = buffer.length();
      }

      if (psiClass.hasTypeParameters()) {
        StringBuilder subst = new StringBuilder();

        PsiTypeParameter[] params = psiClass.getTypeParameters();

        subst.append(LT);
        length += 1;
        boolean goodSubst = true;
        for (int i = 0; i < params.length; i++) {
          PsiType t = psiSubst.substitute(params[i]);

          if (t == null) {
            goodSubst = false;
            break;
          }

          length += generateType(subst, t, context, generateLink, useShortNames);

          if (i < params.length - 1) {
            subst.append(", ");
          }
        }

        subst.append(GT);
        length += 1;
        if (goodSubst) {
          String text = subst.toString();

          buffer.append(text);
        }
      }

      return length;
    }

    if (type instanceof PsiDisjunctionType || type instanceof PsiIntersectionType) {
      if (!generateLink) {
        String canonicalText = useShortNames ? type.getPresentableText() : type.getCanonicalText();
        final String text = StringUtil.escapeXml(canonicalText);
        buffer.append(text);
        return canonicalText.length();
      } else {
        final String separator = type instanceof PsiDisjunctionType ? " | " : " & ";
        final List<PsiType> componentTypes;
        if (type instanceof PsiIntersectionType intersectionType) {
          componentTypes = Arrays.asList(intersectionType.getConjuncts());
        } else {
          componentTypes = ((PsiDisjunctionType) type).getDisjunctions();
        }
        int length = 0;
        for (PsiType psiType : componentTypes) {
          if (length > 0) {
            buffer.append(separator);
            length += 3;
          }
          length += generateType(buffer, psiType, context, true, useShortNames);
        }
        return length;
      }
    }

    return 0;
  }

  @RequiredReadAction
  private static String generateTypeParameters(PsiTypeParameterListOwner owner, boolean useShortNames) {
    if (owner.hasTypeParameters()) {
      PsiTypeParameter[] parms = owner.getTypeParameters();

      StringBuilder buffer = new StringBuilder();
      buffer.append(LT);

      for (int i = 0; i < parms.length; i++) {
        PsiTypeParameter p = parms[i];

        buffer.append(p.getName());

        PsiClassType[] refs = JavaDocUtil.getExtendsList(p);
        if (refs.length > 0) {
          buffer.append(" extends ");
          for (int j = 0; j < refs.length; j++) {
            generateType(buffer, refs[j], owner, true, useShortNames);
            if (j < refs.length - 1) {
              buffer.append(" & ");
            }
          }
        }

        if (i < parms.length - 1) {
          buffer.append(", ");
        }
      }

      buffer.append(GT);
      return buffer.toString();
    }

    return "";
  }

  private <T> Pair<T, InheritDocProvider<T>> searchDocTagInOverriddenMethod(PsiMethod method, PsiClass aSuper, DocTagLocator<T> loc) {
    if (aSuper != null) {
      PsiMethod overridden = findMethodInSuperClass(method, aSuper);
      if (overridden != null) {
        T tag = loc.find(overridden, getDocComment(overridden));
        if (tag != null) {
          return new Pair<>(tag, new InheritDocProvider<T>() {
            @Override
            public Pair<T, InheritDocProvider<T>> getInheritDoc() {
              return findInheritDocTag(overridden, loc);
            }

            @Override
            public PsiClass getElement() {
              return aSuper;
            }
          });
        }
      }
    }

    return null;
  }

  @Nullable
  private static PsiMethod findMethodInSuperClass(PsiMethod method, PsiClass aSuper) {
    for (PsiMethod superMethod : method.findDeepestSuperMethods()) {
      PsiMethod overridden = aSuper.findMethodBySignature(superMethod, false);
      if (overridden != null) {
        return overridden;
      }
    }
    return null;
  }

  @Nullable
  private <T> Pair<T, InheritDocProvider<T>> searchDocTagInSupers(PsiClassType[] supers, PsiMethod method, DocTagLocator<T> loc, Set<PsiClass> visitedClasses) {
    for (PsiClassType superType : supers) {
      PsiClass aSuper = superType.resolve();
      if (aSuper != null) {
        Pair<T, InheritDocProvider<T>> tag = searchDocTagInOverriddenMethod(method, aSuper, loc);
        if (tag != null) {
          return tag;
        }
      }
    }

    for (PsiClassType superType : supers) {
      PsiClass aSuper = superType.resolve();
      if (aSuper != null && visitedClasses.add(aSuper)) {
        Pair<T, InheritDocProvider<T>> tag = findInheritDocTagInClass(method, aSuper, loc, visitedClasses);
        if (tag != null) {
          return tag;
        }
      }
    }

    return null;
  }

  private <T> Pair<T, InheritDocProvider<T>> findInheritDocTagInClass(PsiMethod aMethod, PsiClass aClass, DocTagLocator<T> loc, Set<PsiClass> visitedClasses) {
    if (aClass == null) {
      return null;
    }

    Pair<T, InheritDocProvider<T>> delegate = findInheritDocTagInDelegate(aMethod, loc);
    if (delegate != null) {
      return delegate;
    }

    if (aClass instanceof PsiAnonymousClass anonymousClass) {
      return searchDocTagInSupers(new PsiClassType[]{anonymousClass.getBaseClassType()}, aMethod, loc, visitedClasses);
    }

    PsiClassType[] implementsTypes = aClass.getImplementsListTypes();
    Pair<T, InheritDocProvider<T>> tag = searchDocTagInSupers(implementsTypes, aMethod, loc, visitedClasses);
    if (tag != null) {
      return tag;
    }

    PsiClassType[] extendsTypes = aClass.getExtendsListTypes();
    return searchDocTagInSupers(extendsTypes, aMethod, loc, visitedClasses);
  }

  @Nullable
  private <T> Pair<T, InheritDocProvider<T>> findInheritDocTagInDelegate(PsiMethod method, DocTagLocator<T> loc) {
    PsiMethod delegateMethod = findDelegateMethod(method);
    if (delegateMethod == null) {
      return null;
    }

    PsiClass containingClass = delegateMethod.getContainingClass();
    if (containingClass == null) {
      return null;
    }

    T tag = loc.find(delegateMethod, getDocComment(delegateMethod));
    if (tag == null) {
      return null;
    }

    return Pair.create(tag, new InheritDocProvider<T>() {
      @Override
      public Pair<T, InheritDocProvider<T>> getInheritDoc() {
        return findInheritDocTag(delegateMethod, loc);
      }

      @Override
      public PsiClass getElement() {
        return containingClass;
      }
    });
  }

  @Nullable
  private static PsiMethod findDelegateMethod(@Nonnull PsiMethod method) {
    PsiDocCommentOwner delegate = DocumentationDelegateProvider.findDocumentationDelegate(method);
    return delegate instanceof PsiMethod psiMethod ? psiMethod : null;
  }

  @Nullable
  private <T> Pair<T, InheritDocProvider<T>> findInheritDocTag(PsiMethod method, DocTagLocator<T> loc) {
    PsiClass aClass = method.getContainingClass();
    return aClass != null ? findInheritDocTagInClass(method, aClass, loc, new HashSet<>()) : null;
  }

  private static class ParamInfo {
    private final String name;
    private final PsiDocTag docTag;
    private final InheritDocProvider<PsiDocTag> inheritDocTagProvider;

    private ParamInfo(String paramName, PsiDocTag tag, InheritDocProvider<PsiDocTag> provider) {
      name = paramName;
      docTag = tag;
      inheritDocTagProvider = provider;
    }

    private ParamInfo(String paramName, @Nonnull Pair<PsiDocTag, InheritDocProvider<PsiDocTag>> tagWithInheritProvider) {
      this(paramName, tagWithInheritProvider.first, tagWithInheritProvider.second);
    }
  }

  private static class ReturnTagLocator implements DocTagLocator<PsiDocTag> {
    @Override
    public PsiDocTag find(PsiDocCommentOwner owner, PsiDocComment comment) {
      return comment != null ? comment.findTagByName("return") : null;
    }
  }

  private static class MyVisitor extends JavaElementVisitor {
    private final StringBuilder myBuffer;

    MyVisitor(@Nonnull StringBuilder buffer) {
      myBuffer = buffer;
    }

    @Override
    public void visitNewExpression(PsiNewExpression expression) {
      myBuffer.append("new ");
      PsiType type = expression.getType();
      if (type != null) {
        generateType(myBuffer, type, expression);
      }
      PsiExpression[] dimensions = expression.getArrayDimensions();
      if (dimensions.length > 0) {
        LOG.assertTrue(myBuffer.charAt(myBuffer.length() - 1) == ']');
        myBuffer.setLength(myBuffer.length() - 1);
        for (PsiExpression dimension : dimensions) {
          dimension.accept(this);
          myBuffer.append(", ");
        }
        myBuffer.setLength(myBuffer.length() - 2);
        myBuffer.append(']');
      } else {
        expression.acceptChildren(this);
      }
    }

    @Override
    public void visitExpressionList(PsiExpressionList list) {
      myBuffer.append("(");
      String separator = ", ";
      PsiExpression[] expressions = list.getExpressions();
      for (PsiExpression expression : expressions) {
        expression.accept(this);
        myBuffer.append(separator);
      }
      if (expressions.length > 0) {
        myBuffer.setLength(myBuffer.length() - separator.length());
      }
      myBuffer.append(")");
    }

    @Override
    @RequiredReadAction
    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
      myBuffer.append(StringUtil.escapeXml(expression.getMethodExpression().getText()));
      expression.getArgumentList().accept(this);
    }

    @Override
    @RequiredReadAction
    public void visitExpression(PsiExpression expression) {
      myBuffer.append(StringUtil.escapeXml(expression.getText()));
    }

    @Override
    @RequiredReadAction
    public void visitReferenceExpression(PsiReferenceExpression expression) {
      myBuffer.append(StringUtil.escapeXml(expression.getText()));
    }
  }
}