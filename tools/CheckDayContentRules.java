import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.obsnotes.DayContentRules;

/** Read-only bridge for comparing the native parser and counters with the real JS module. */
public final class CheckDayContentRules {
  public static void main(String[] args) throws Exception {
    var rules = DayContentRules.parse(Files.readString(Path.of(args[0])));
    if (args.length == 1) {
      for (String line : rules.excludedLines()) System.out.println("line\t" + encode(line));
      for (String line : rules.excludedContains()) System.out.println("contains\t" + encode(line));
    } else {
      for (String line : Files.readAllLines(Path.of(args[1]))) {
        String decoded = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
        System.out.println(rules.hidden(decoded) + "\t" + rules.countable(decoded));
      }
    }
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
