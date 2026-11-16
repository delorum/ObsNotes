import java.nio.file.*;
import ru.obsnotes.CleanupRules;

/** Read-only bridge for checking that Java and Obsidian parse the same vault configuration. */
public final class CheckCleanupRules {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected the shared settings path");
    for (String marker :
        new java.util.TreeSet<>(CleanupRules.parse(Files.readString(Path.of(args[0]))).markers))
      System.out.println(marker);
  }
}
