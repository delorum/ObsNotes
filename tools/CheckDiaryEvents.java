import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import ru.obsnotes.*;

/** Bidirectional relationship checks on synthetic input supplied by the vault's JS tests. */
public final class CheckDiaryEvents {
  private static String quote(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        + "\"";
  }

  public static void main(String[] args) throws Exception {
    String mode = args[0], path = args[1];
    Markdown doc = new Markdown(Files.readString(Path.of(args[2])));
    var middle =
        doc.blocks.stream()
            .filter(b -> CrossReferences.clean(b.text()).startsWith("Middle"))
            .findFirst()
            .orElse(null);
    if (mode.equals("inspect")) {
      String items =
          doc.blocks.stream()
              .map(
                  b ->
                      "{\"start\":"
                          + b.start()
                          + ",\"end\":"
                          + b.end()
                          + ",\"id\":"
                          + quote(Events.rootId(b.text()))
                          + ",\"caption\":"
                          + quote(Events.caption(b))
                          + ",\"parents\":["
                          + Events.parents(path, b.text()).stream()
                              .map(p -> quote(p.path() + "#^" + p.id()))
                              .collect(Collectors.joining(","))
                          + "]}")
              .collect(Collectors.joining(","));
      String candidates =
          new DiaryIndex()
              .roots(path, doc, -1).stream()
                  .map(r -> Integer.toString(r.block().start()))
                  .collect(Collectors.joining(","));
      System.out.println(
          "{\"blocks\":"
              + doc.blocks.size()
              + ",\"roots\":"
              + doc.blocks.stream().filter(b -> !Events.rootId(b.text()).isEmpty()).count()
              + ",\"parentLinks\":"
              + doc.blocks.stream().mapToInt(b -> Events.parents(path, b.text()).size()).sum()
              + ",\"middleCaption\":"
              + quote(middle == null ? "" : Events.caption(middle))
              + ",\"items\":["
              + items
              + "],\"candidates\":["
              + candidates
              + "]}");
      return;
    }
    Markdown template = new Markdown("## Заметки\n");
    var edited = mode.equals("unroot") ? middle : doc.blocks.get(doc.blocks.size() - 1);
    if (edited == null || middle == null)
      throw new IllegalArgumentException("Synthetic middle block required");
    var state = EditorState.editing(path, doc, template, edited);
    state.enableParts(template);
    if (mode.equals("unroot")) state.parts.deleteRoot(0);
    else if (mode.equals("promote")) {
      var candidate =
          new DiaryIndex()
              .roots(path, doc, edited.start()).stream()
                  .filter(r -> r.block().start() == middle.start())
                  .findFirst()
                  .orElseThrow();
      state.parts.addExternal(0, candidate, doc.text, null);
    } else throw new IllegalArgumentException(mode);
    LocalDate date = WorkDaily.date(path);
    var changes = VaultRepository.plan(state, doc, doc, template, date, date.atTime(12, 0));
    String output =
        changes.stream().filter(c -> c.path().equals(path)).findFirst().orElseThrow().after();
    System.out.println(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
  }
}
