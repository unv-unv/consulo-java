package com.intellij.java.analysis.impl.find.findUsages;

import consulo.find.FindBundle;
import consulo.project.Project;

import jakarta.annotation.Nonnull;
import java.util.LinkedHashSet;

/**
 * @author peter
 */
public class JavaPackageFindUsagesOptions extends JavaFindUsagesOptions {
  public boolean isClassesUsages = false;
  public boolean isIncludeSubpackages = true;
  public boolean isSkipPackageStatements = false;

  public JavaPackageFindUsagesOptions(@Nonnull Project project) {
    super(project);
  }

  @Override
  protected void addUsageTypes(LinkedHashSet<String> to) {
    if (this.isUsages || this.isClassesUsages) {
      to.add(FindBundle.message("find.usages.panel.title.usages"));
    }
  }

  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!super.equals(this)) return false;
    if (o == null || getClass() != o.getClass()) return false;

    final JavaPackageFindUsagesOptions that = (JavaPackageFindUsagesOptions)o;

    if (isClassesUsages != that.isClassesUsages) return false;
    if (isIncludeSubpackages != that.isIncludeSubpackages) return false;
    if (isSkipPackageStatements != that.isSkipPackageStatements) return false;

    return true;
  }

  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + (isClassesUsages ? 1 : 0);
    result = 31 * result + (isIncludeSubpackages ? 1 : 0);
    result = 31 * result + (isSkipPackageStatements ? 1 : 0);
    return result;
  }

}
