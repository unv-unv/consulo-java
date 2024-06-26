package com.siyeh.ipp.decls;

import com.siyeh.IntentionPowerPackBundle;
import com.siyeh.ipp.IPPTestCase;

/**
 * @author Bas Leijdekkers
 */
public abstract class ChangeVariableTypeToRhsTypeIntentionTest extends IPPTestCase {

  public void testSimple() { doTest("Declare 'ss' with type 'ArrayList<String>'"); }

  public void testSameType() { assertIntentionNotAvailable(); }

  @Override
  protected String getIntentionName() {
    return IntentionPowerPackBundle.message("change.variable.type.to.rhs.type.intention.name", "ss", "ArrayList<String>");
  }

  @Override
  protected String getRelativePath() {
    return "decls/change_variable_type_to_rhs_type";
  }
}
