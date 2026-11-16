package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class DiaryCleanupTest {
  private static final CleanupRules RULES =
      CleanupRules.parse("## Пустые маркеры\n- вчера\n- позавчера\n");

  @Test
  public void collapsesBlanksAndDropsOrphanStampsAndWords() {
    String text =
        "## Заметки\n\n\n"
            + "2026-11-15 10:00:00\n\n"
            + "вчера\n\n"
            + "позавчера\n\n"
            + "2026-11-15 11:00:00\n"
            + "событие\n\n\n"
            + "## Дела\n";
    assertEquals(
        "## Заметки\n\n2026-11-15 11:00:00\nсобытие\n\n## Дела\n",
        DiaryCleanup.compress(text, RULES));
  }

  @Test
  public void keepsWordsAndTimestampsThatHaveFollowingContent() {
    String text = "## Заметки\n2026-11-15 10:00:00\nвчера\nсобытие ^anchor\n";
    assertEquals(text, DiaryCleanup.compress(text, RULES));
  }

  @Test
  public void dropsStampsAtEndAndBeforeHeadingsOrAnotherStamp() {
    assertEquals(
        "## Заметки\n2026-11-15 11:00:00\nтекст\n## Далее\n",
        DiaryCleanup.compress(
            "## Заметки\n"
                + "2026-11-15 10:00:00\n"
                + "2026-11-15 11:00:00\n"
                + "текст\n"
                + "2026-11-15 12:00:00\n"
                + "## Далее\n"
                + "2026-11-15 13:00:00\n",
            RULES));
  }

  @Test
  public void protectsFrontmatterFencesAndQuotedCalloutsAndKeepsCrLf() {
    String text =
        "---\n"
            + "key: value\n\n\n"
            + "---\n"
            + "## Заметки\n\n"
            + "````js\n"
            + "вчера\n\n\n"
            + "```\n"
            + "2026-11-15 10:00:00\n"
            + "````\n\n"
            + "> [!info]- Дополнения\n"
            + "> вчера\n"
            + ">\n"
            + ">\n"
            + "> ```dataviewjs\n"
            + "> script();\n"
            + "> ```\n";
    text = text.replace("\n", "\r\n");
    assertEquals(text, DiaryCleanup.compress(text, RULES));
  }

  @Test
  public void compressionIsIdempotentWithAndWithoutFinalNewline() {
    for (String text : new String[] {"a\n\n\nвчера", "a\n\n\nвчера\n", "\n\n", ""}) {
      String once = DiaryCleanup.compress(text, RULES);
      assertEquals(once, DiaryCleanup.compress(once, RULES));
      assertFalse(once.contains("\n\n\n"));
    }
  }

  @Test
  public void movingRootWithinDayCleansSourceAndRetainsCallout() {
    String root = Events.root("событие");
    var from = new Markdown("## Заметки\n\n2026-11-15 10:00:00\n" + root + "\n\n\n## Дела\n");
    var s = new EditorState();
    var b = from.blocks.get(0);
    s.fresh = false;
    s.sourcePath = s.destinationPath = "day.md";
    s.sourceSnapshot = s.destinationSnapshot = from.text;
    s.original = s.text = b.text();
    s.start = b.start();
    s.end = b.end();
    s.heading = "## Дела";
    String after =
        VaultRepository.plan(s, from, from, from, LocalDate.now(), LocalDateTime.now(), RULES)
            .get(0)
            .after();
    assertFalse(after.contains("10:00:00"));
    assertFalse(after.contains("\n\n\n"));
    assertTrue(after.contains(root));
  }

  @Test
  public void moveAcrossDaysCompactsBothFilesButPreservesTransactionSnapshots() {
    var from = new Markdown("## Заметки\n\n2026-11-15 10:00:00\nсобытие\n\n\nвчера\n\n");
    var to = new Markdown("## Заметки\n\n\n2026-11-14 10:00:00\n\n## Дела\n\n\n");
    var s = new EditorState();
    var b = from.blocks.get(0);
    s.fresh = false;
    s.sourcePath = "today.md";
    s.destinationPath = "past.md";
    s.original = s.text = b.text();
    s.start = b.start();
    s.end = b.end();
    s.heading = "## Дела";
    var changes =
        VaultRepository.plan(
            s, from, to, to, LocalDate.of(2026, 11, 14), LocalDateTime.now(), RULES);
    assertEquals(to.text, changes.get(0).before());
    assertEquals(from.text, changes.get(1).before());
    assertEquals("## Заметки\n\n", changes.get(1).after());
    assertFalse(changes.get(0).after().contains("10:00:00"));
    assertFalse(changes.get(0).after().contains("\n\n\n"));
    assertTrue(changes.get(0).after().contains("событие"));
  }

  @Test
  public void freshInsertionIntoPastDoesNotCompactUntouchedSource() {
    var from = new Markdown("## Заметки\n\n\nвчера\n\n");
    var to = new Markdown("## Заметки\n\n## Дела\n");
    var s = new EditorState();
    s.sourcePath = "today.md";
    s.destinationPath = "past.md";
    s.text = "событие";
    s.heading = "## Дела";
    var changes =
        VaultRepository.plan(s, from, to, to, LocalDate.now(), LocalDateTime.now(), RULES);
    assertEquals(from.text, changes.get(1).after());
  }
}
