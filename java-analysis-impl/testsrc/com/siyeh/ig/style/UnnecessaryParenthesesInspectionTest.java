package com.siyeh.ig.style;

import consulo.language.editor.inspection.scheme.LocalInspectionToolWrapper;
import com.intellij.java.language.LanguageLevel;
import com.siyeh.ig.IGInspectionTestCase;

public class UnnecessaryParenthesesInspectionTest extends IGInspectionTestCase {

  public void test() throws Exception {
    final UnnecessaryParenthesesInspection inspection = new UnnecessaryParenthesesInspection();
    inspection.ignoreParenthesesOnConditionals = true;
    final LanguageLevelProjectExtension levelProjectExtension = LanguageLevelProjectExtension.getInstance(getProject());
    final LanguageLevel level = levelProjectExtension.getLanguageLevel();
    try {
      levelProjectExtension.setLanguageLevel(LanguageLevel.JDK_1_8);
      doTest("com/siyeh/igtest/style/unnecessary_parentheses",
             new LocalInspectionToolWrapper(inspection), "java 1.8");
    }
    finally {
      levelProjectExtension.setLanguageLevel(level);
    }
  }

  public void testClarifyingParentheses() {
    final UnnecessaryParenthesesInspection inspection = new UnnecessaryParenthesesInspection();
    inspection.ignoreClarifyingParentheses = true;
    doTest("com/siyeh/igtest/style/clarifying_parentheses", inspection);
  }
}