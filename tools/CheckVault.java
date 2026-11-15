import java.nio.file.*;
import java.util.*;
import ru.obsnotes.*;

/** Read-only compatibility smoke check. Prints aggregates, never diary contents. */
public final class CheckVault {
  public static void main(String[] args) throws Exception {
    Path vault = Path.of(args[0]);
    Markdown template =
        TemplateCatalog.parse(Files.readString(vault.resolve(TemplateCatalog.PATH)));
    int files = 0, blocks = 0, roots = 0;
    try (var paths = Files.walk(vault.resolve("Дневник/Хроника"))) {
      for (Path path :
          paths
              .filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}-[а-я]{2}\\.md"))
              .collect(java.util.stream.Collectors.toList())) {
        String source = Files.readString(path);
        Markdown document = new Markdown(source);
        for (var b : document.blocks) {
          if (!source.equals(document.replace(b, b.text()).text))
            throw new AssertionError("Identity replacement changed bytes");
          blocks++;
          if (!Events.id(b.text()).isEmpty()) roots++;
        }
        files++;
      }
    }
    Markdown empty = new Markdown("## Заметки\n");
    for (var section : template.sections)
      empty.append(section.heading(), "compatibility check", template);
    System.out.printf(
        "Read-only vault check: %d files, %d blocks, %d anchors, %d template sections, %d"
            + " crossPeople destinations.%n",
        files,
        blocks,
        roots,
        template.sections.size(),
        CrossReferences.destinations(template).size());
  }
}
