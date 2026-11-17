package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class RootDestinationTest {
  static final LocalDate DATE = LocalDate.of(2026, 11, 17);
  static final String TODAY = DiaryLayout.path(DATE),
      PAST = DiaryLayout.path(DATE.minusDays(1)),
      FUTURE = DiaryLayout.path(DATE.plusDays(1));
  static final Markdown TEMPLATE = new Markdown("## Заметки\n");

  static final class Storage implements VaultStorage {
    final Map<String, String> files = new HashMap<>();
    final List<String> reads = new ArrayList<>();

    public String read(String path) throws IOException {
      reads.add(path);
      if (!files.containsKey(path)) throw new FileNotFoundException(path);
      return files.get(path);
    }

    public void write(String path, String text) {
      throw new AssertionError("Picker must not write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Picker must not enumerate");
    }
  }

  @Test
  public void selectedExistingDayAloneIsReadAndSameOffsetInAnotherFileIsNotExcluded()
      throws Exception {
    var storage = new Storage();
    var source = new Markdown("## Заметки\n\nSourceOnly\n");
    var form = EditorState.editing(TODAY, source, TEMPLATE, source.blocks.get(0));
    for (String path : List.of(PAST, FUTURE)) {
      var target = new Markdown("## Заметки\n\nSelectedOnly\n");
      storage.files.put(path, target.text);
      form.chooseDay(path, target.text);
      var roots = new DiaryIndex().roots(storage, form);
      assertEquals(1, roots.size());
      assertEquals(path, roots.get(0).path());
      assertEquals("SelectedOnly", roots.get(0).block().text());
    }
    assertEquals(List.of(PAST, FUTURE), storage.reads);
  }

  @Test
  public void returningToSourceExcludesOnlyTheActuallyEditedBlock() throws Exception {
    var s = new Storage();
    var doc = new Markdown("## Заметки\n\nEdited\n\nOther\n");
    s.files.put(TODAY, doc.text);
    var form = EditorState.editing(TODAY, doc, TEMPLATE, doc.blocks.get(0));
    form.chooseDay(PAST, doc.text);
    form.chooseDay(TODAY, doc.text);
    var roots = new DiaryIndex().roots(s, form);
    assertEquals(1, roots.size());
    assertEquals("Other", roots.get(0).block().text());
    assertEquals(List.of(TODAY), s.reads);
  }

  @Test
  public void missingSelectedFileDoesNotFallBackToSourceOrScanArchive() {
    var s = new Storage();
    var doc = new Markdown("## Заметки\n\nSource\n");
    s.files.put(TODAY, doc.text);
    var form = EditorState.newNote(TODAY, doc, TEMPLATE, "## Заметки");
    form.chooseDay(PAST, doc.text);
    assertThrows(FileNotFoundException.class, () -> new DiaryIndex().roots(s, form));
    assertEquals(List.of(PAST), s.reads);
  }

  @Test
  public void selectedDaysNewRootAndChildAreCommittedTogetherBeforeSourceRemoval()
      throws Exception {
    var source = new Markdown("## Заметки\n\nMoveMe\n");
    var dest = new Markdown("## Заметки\n\nSelectedRoot\n");
    var s = new Storage();
    s.files.put(PAST, dest.text);
    var owner = EditorState.editing(TODAY, source, TEMPLATE, source.blocks.get(0));
    owner.enableParts(TEMPLATE);
    owner.chooseDay(PAST, dest.text);
    var form = owner.activePart();
    var root = new DiaryIndex().roots(s, form).get(0);
    owner.parts.addExternal(0, root, dest.text, null);
    var changes =
        VaultRepository.plan(owner, source, dest, TEMPLATE, DATE.minusDays(1), DATE.atTime(12, 0));
    assertEquals(List.of(PAST, TODAY), changes.stream().map(VaultRepository.Change::path).toList());
    var result = new Markdown(changes.get(0).after());
    var parent =
        result.blocks.stream()
            .filter(b -> !Events.rootId(b.text()).isEmpty())
            .findFirst()
            .orElseThrow();
    var child =
        result.blocks.stream().filter(b -> b.text().contains("MoveMe")).findFirst().orElseThrow();
    assertEquals(
        new Events.Parent(PAST, Events.rootId(parent.text())),
        Events.parent(PAST, child.text()).orElseThrow());
    assertFalse(changes.get(1).after().contains("MoveMe"));
  }

  @Test
  public void aChangedDestinationSnapshotRejectsSelectionWithoutChangingTheEditor()
      throws Exception {
    var storage = new Storage();
    var source = new Markdown("## Заметки\n\nMoveMe\n");
    var dest = new Markdown("## Заметки\n\nSelectedRoot\n");
    var form = EditorState.editing(TODAY, source, TEMPLATE, source.blocks.get(0));
    form.chooseDay(PAST, dest.text);
    storage.files.put(PAST, dest.text);
    var index = new DiaryIndex();
    var root = index.roots(storage, form).get(0);
    assertEquals(dest.text, index.rootSnapshot(storage, form, root));
    storage.files.put(PAST, dest.text + "\nExternal change\n");
    assertThrows(VaultRepository.Conflict.class, () -> index.rootSnapshot(storage, form, root));
    assertEquals("MoveMe", form.editableText());
    assertTrue(form.rootEdits.isEmpty());
  }

  @Test
  public void staleRootFromAnEarlierDateIsRejectedBeforeAnyRead() {
    var storage = new Storage();
    var source = new Markdown("## Заметки\n\nMoveMe\n");
    var form = EditorState.editing(TODAY, source, TEMPLATE, source.blocks.get(0));
    form.chooseDay(PAST, source.text);
    var oldRoot = new Events.Root(TODAY, source.blocks.get(0), "");
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiaryIndex().rootSnapshot(storage, form, oldRoot));
    assertTrue(storage.reads.isEmpty());
  }
}
