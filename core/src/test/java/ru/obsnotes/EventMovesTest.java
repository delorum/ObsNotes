package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public class EventMovesTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 23);
  private static final String TODAY = DiaryLayout.path(DAY);
  private static final String PAST = DiaryLayout.path(DAY.minusDays(1));
  private static final String FUTURE = DiaryLayout.path(DAY.plusDays(1));
  private static final String EXTERNAL = "События/Список.md";
  private static final String ROOT = Events.root("Root ^event-one");
  private static final Markdown TEMPLATE = doc("");

  private static Markdown doc(String body) {
    return new Markdown(
        "## Заметки\n\n" + body + "\n\n## Задачи\n\n" + "- [ ] #task/diary Test fact\n");
  }

  private static String link(String path, String id, String label) {
    return "[[" + path.replaceFirst("\\.md$", "") + "#^" + id + "|" + label + "]]";
  }

  private static EditorState editor(Markdown source) {
    EditorState state = EditorState.editing(TODAY, source, TEMPLATE, source.blocks.get(0));
    state.enableParts(TEMPLATE);
    return state;
  }

  private static final class Storage implements VaultStorage {
    final Map<String, String> files = new LinkedHashMap<>();
    final List<String> writes = new ArrayList<>();
    int visited;

    public String read(String path) throws IOException {
      String text = files.get(path);
      if (text == null) throw new FileNotFoundException(path);
      return text;
    }

    public void write(String path, String text) {
      writes.add(path);
      files.put(path, text);
    }

    public List<String> dailyNotes() {
      return files.keySet().stream().filter(path -> path.startsWith("Дневник/")).toList();
    }

    public void visitMarkdownNotes(BooleanSupplier cancelled, NoteVisitor visitor)
        throws IOException {
      visited++;
      for (String path : List.copyOf(files.keySet())) visitor.visit(path);
    }
  }

  private static final class Journal implements VaultRepository.Journal {
    List<VaultRepository.Change> changes = List.of();

    public void prepare(List<VaultRepository.Change> changes) {
      this.changes = changes;
    }

    public List<VaultRepository.Change> pending() {
      return changes;
    }

    public void complete() {
      changes = List.of();
    }
  }

  private static List<VaultRepository.Change> plan(VaultRepository repository, EditorState state)
      throws IOException {
    return repository.planEditor(
        state,
        new Markdown(state.sourceSnapshot),
        new Markdown(state.destinationSnapshot),
        TEMPLATE,
        DAY,
        DAY.atTime(12, 0),
        CleanupRules.EMPTY);
  }

  @Test
  public void movingAnchorWritesTheDestinationThenRepointsLinksThenRemovesTheOldRoot()
      throws IOException {
    String local = link("", "event-one", "Local");
    String old = link(TODAY, "event-one", "Remote");
    String foreign = link(FUTURE, "event-one", "Unrelated");
    var storage = new Storage();
    var source = doc(ROOT + "\n\n" + local + "\nLocal child");
    var destination = doc("Previous");
    storage.files.put(TODAY, source.text);
    storage.files.put(PAST, destination.text);
    storage.files.put(
        FUTURE,
        doc(old + "\nRemote child\n\n" + foreign + "\nOther\n\n" + "```md\n" + old + "\n```").text);
    storage.files.put(EXTERNAL, "Inline " + old + " and `" + old + "`\n");
    var repository = new VaultRepository(storage, new Journal());
    var state = editor(source);
    state.activePart().chooseDay(PAST, destination.text);
    var changes = plan(repository, state);
    assertEquals(1, storage.visited);
    assertEquals(
        List.of(PAST, FUTURE, EXTERNAL, TODAY),
        changes.stream().map(VaultRepository.Change::path).toList());
    repository.commit(changes);
    assertEquals(List.of(PAST, FUTURE, EXTERNAL, TODAY), storage.writes);
    assertTrue(storage.read(PAST).contains(ROOT));
    assertFalse(storage.read(TODAY).contains("Root ^event-one"));
    assertTrue(storage.read(TODAY).contains(link(PAST, "event-one", "Local") + "\nLocal child"));
    assertTrue(storage.read(FUTURE).contains(link(PAST, "event-one", "Remote") + "\nRemote child"));
    assertTrue(storage.read(FUTURE).contains(foreign + "\nOther"));
    assertTrue(storage.read(FUTURE).contains("```md\n" + old + "\n```"));
    assertEquals(
        "Inline " + link(PAST, "event-one", "Remote") + " and `" + old + "`\n",
        storage.read(EXTERNAL));
  }

  @Test
  public void independentPartsCanKeepTheirOriginalPositionOrUseTwoDifferentDates()
      throws IOException {
    var storage = new Storage();
    var source = doc("First\n\nNeighbour");
    var past = doc("Previous");
    var future = doc("Following");
    storage.files.put(TODAY, source.text);
    storage.files.put(PAST, past.text);
    storage.files.put(FUTURE, future.text);
    var repository = new VaultRepository(storage, new Journal());
    var state = editor(source);
    state.edit("First\n\nStays\n\nThird");
    state.parts.parts.get(0).form().chooseDay(PAST, past.text);
    state.parts.parts.get(2).form().chooseDay(FUTURE, future.text);
    var changes = plan(repository, state);
    assertEquals(
        List.of(PAST, FUTURE, TODAY), changes.stream().map(VaultRepository.Change::path).toList());
    repository.commit(changes);
    assertTrue(storage.read(PAST).contains("First"));
    assertTrue(storage.read(FUTURE).contains("Third"));
    assertTrue(storage.read(TODAY).contains("Stays\n\nNeighbour"));
    assertFalse(storage.read(TODAY).contains("First"));
    assertFalse(storage.read(TODAY).contains("Third"));
    assertEquals(0, storage.visited);
  }

  @Test
  public void newInternalLinksUseTheDatesOfEachPartAndTasksUseTheirDay() throws IOException {
    var storage = new Storage();
    var source = doc("First");
    var past = doc("Previous");
    var future = doc("Following");
    var repository = new VaultRepository(storage, new Journal());
    var state = editor(source);
    state.edit("First\n\nSecond\n\nThird");
    state.parts.parts.get(0).form().chooseDay(PAST, past.text);
    var task = state.parts.parts.get(2).form();
    task.chooseDay(FUTURE, future.text);
    task.chooseTarget(
        future.targets(TEMPLATE).stream()
            .filter(t -> t.kind() == Markdown.Kind.TASK)
            .findFirst()
            .orElseThrow());
    state.parts.addInternal(1, 0, null);
    var changes = plan(repository, state);
    var pasted = new Markdown(changes.get(0).after());
    var root =
        pasted.blocks.stream()
            .filter(b -> !Events.rootId(b.text()).isEmpty())
            .findFirst()
            .orElseThrow();
    assertTrue(
        changes.stream()
            .filter(c -> c.path().equals(TODAY))
            .findFirst()
            .orElseThrow()
            .after()
            .contains(link(PAST, Events.rootId(root.text()), "First") + "\n"));
    var updatedTask =
        changes.stream().filter(c -> c.path().equals(FUTURE)).findFirst().orElseThrow();
    assertTrue(updatedTask.after(), updatedTask.after().contains(DAY.plusDays(1).toString()));
    assertEquals(0, storage.visited);
  }

  @Test
  public void movingASelectedRootAndItsSelectedChildUsesTheirSeparateDates() throws IOException {
    var storage = new Storage();
    var source = doc(ROOT + "\n\n" + link("", "event-one", "Local") + "\nChild");
    var past = doc("Past");
    var future = doc("Future");
    storage.files.put(TODAY, source.text);
    storage.files.put(PAST, past.text);
    storage.files.put(FUTURE, future.text);
    var selection = new BlockSelection();
    selection.toggle(TODAY, source, source.blocks.get(0));
    selection.toggle(TODAY, source, source.blocks.get(1));
    var state =
        EditorBatch.create(
            TODAY, source, TEMPLATE, null, selection.ordered(TODAY, Map.of(TODAY, source)));
    state.parts.parts.get(0).form().chooseDay(PAST, past.text);
    state.parts.parts.get(1).form().chooseDay(FUTURE, future.text);
    var repository = new VaultRepository(storage, new Journal());
    repository.commit(plan(repository, state));
    assertEquals(1, storage.visited);
    assertEquals(List.of(PAST, FUTURE, TODAY), storage.writes);
    assertTrue(storage.read(PAST).contains(ROOT));
    assertTrue(storage.read(FUTURE).contains(link(PAST, "event-one", "Root") + "\nChild"));
    assertFalse(storage.read(TODAY).contains("Child"));
    assertFalse(storage.read(TODAY).contains(ROOT));
  }

  @Test
  public void duplicateDestinationAnchorRejectsMoveBeforeEnumeratingOrWriting() {
    var storage = new Storage();
    var source = doc(ROOT);
    var destination = doc(Events.root("Another root ^event-one"));
    var state = editor(source);
    state.activePart().chooseDay(PAST, destination.text);
    var repository = new VaultRepository(storage, new Journal());
    assertThrows(IllegalArgumentException.class, () -> plan(repository, state));
    assertEquals(0, storage.visited);
    assertTrue(storage.writes.isEmpty());
  }

  @Test
  public void changedBacklinkStopsAllWritesAndDifferentDatesCannotMergeByAccident()
      throws IOException {
    var storage = new Storage();
    var source = doc(ROOT);
    var target = doc("Past");
    storage.files.put(TODAY, source.text);
    storage.files.put(PAST, target.text);
    storage.files.put(EXTERNAL, link(TODAY, "event-one", "Old"));
    var repository = new VaultRepository(storage, new Journal());
    var state = editor(source);
    state.activePart().chooseDay(PAST, target.text);
    var changes = plan(repository, state);
    storage.files.put(EXTERNAL, "External change");
    assertThrows(VaultRepository.Conflict.class, () -> repository.commit(changes));
    assertTrue(storage.writes.isEmpty());

    state.edit("Root\n\nAnother");
    state.parts.parts.get(1).form().chooseDay(TODAY, source.text);
    state.edit("Root\nAnother");
    assertFalse(state.parts.error.isEmpty());
    assertEquals(2, state.parts.parts.size());
  }
}
