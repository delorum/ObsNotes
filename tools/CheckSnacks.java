import java.nio.charset.StandardCharsets;
import java.util.Base64;
import ru.obsnotes.*;

/** Read-only bridge for synthetic JS/native snack checklist parity cases. */
public final class CheckSnacks {
  public static void main(String[] args) {
    var doc = new Markdown(new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8));
    var context = new DailyChecklist.Context(0L, null, false, null, null);
    boolean snacks =
        DailyChecklist.rows(doc, context).stream()
            .filter(row -> row.label().equals("Питание"))
            .findFirst()
            .orElseThrow()
            .status()
            .get(3)
            .ok();
    System.out.println(snacks);
  }
}
