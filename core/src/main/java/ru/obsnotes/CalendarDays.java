package ru.obsnotes;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Calendar status is derived read-only from one month's exact daily paths. */
public final class CalendarDays {
  private CalendarDays() {}

  public enum State {
    MISSING,
    REVIEW,
    COMPLETE
  }

  public record Day(LocalDate date, State state) {}

  public static State assess(Markdown note, DayContentRules rules) {
    String description = CrossReferences.clean("ShortDescr:: " + PastDays.description(note.text));
    description = description.substring("ShortDescr::".length()).strip();
    if (DayContentRules.emptyDataviewValue(description)) return State.REVIEW;
    Set<Integer> live = new HashSet<>();
    for (var line : MarkdownLines.scan(note.text)) if (!line.literal()) live.add(line.start());
    for (var notes : note.sections) {
      if (!notes.heading().equals("## Заметки") || !live.contains(notes.start())) continue;
      int end = note.text.length();
      for (var section : note.sections)
        if (section.start() > notes.start()
            && section.level() <= notes.level()
            && live.contains(section.start())) {
          end = section.start();
          break;
        }
      for (var block : note.blocks)
        if (block.start() >= notes.body()
            && block.start() < end
            && live.contains(block.section().start())
            && rules.view(block.text()).counted()) return State.REVIEW;
    }
    return State.COMPLETE;
  }

  public static void load(
      VaultStorage storage,
      YearMonth month,
      DayContentRules rules,
      Set<LocalDate> known,
      BooleanSupplier cancelled,
      Consumer<Day> loaded)
      throws IOException {
    for (int i = 1; i <= month.lengthOfMonth(); i++) {
      if (cancelled.getAsBoolean()) return;
      LocalDate date = month.atDay(i);
      if (known.contains(date)) continue;
      State state;
      try {
        String path = DiaryLayout.path(date);
        boolean exists = storage.exists(path);
        if (cancelled.getAsBoolean()) return;
        state = exists ? assess(new Markdown(storage.read(path)), rules) : State.MISSING;
      } catch (IOException error) {
        if (!DayNavigation.missing(error)) throw error;
        state = State.MISSING;
      }
      if (cancelled.getAsBoolean()) return;
      loaded.accept(new Day(date, state));
    }
  }
}
