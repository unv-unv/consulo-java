import jakarta.annotation.Nonnull;

import java.io.File;

class Zoo2 {
  public static boolean startsWith(@Nonnull String path, @jakarta.annotation.Nonnull String start, final boolean caseSensitive) {
    final int length1 = path.length();
    final int length2 = start.length();
    if (length2 == 0) return true;
    if (length2 > length1) return false;
    if (!path.regionMatches(!caseSensitive, 0, start, 0, length2)) return false;
    if (length1 == length2) return true;
    char last2 = start.charAt(length2 - 1);
    char next1;
    if (last2 == '/' || last2 == File.separatorChar) {
      next1 = path.charAt(length2 - 1);
    }
    else {
      next1 = path.charAt(length2);
    }
    return next1 == '/' || next1 == File.separatorChar;
  }
  void foo(Some me, Some other) {
    if (me.depth < other.depth) {
      System.out.println("less");
    } else if (other.depth > me.depth) {
      System.out.println("more");
    }
  }
}

class Some {
  final int depth;

  Some(int depth) {
    this.depth = depth;
  }
}