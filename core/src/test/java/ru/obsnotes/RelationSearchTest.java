package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class RelationSearchTest {
  @Test
  public void leadingLinksWrittenForTaskLikeRootLabelsRemainRecognizable() {
    Markdown note = new Markdown(doc(Events.root("- [x] A task ^event-root")));
    String linked =
        Events.link("Addition", new Events.Root(TODAY, note.blocks.get(0), "event-root"));
    assertTrue(Events.hasRelations(TODAY, linked));
    assertEquals(TODAY, Events.parent(TODAY, linked).orElseThrow().path());
  }

  @Test
  public void rootPickerDoesNotTreatAnAnchorWithoutCalloutAsExistingRoot() {
    Markdown source = new Markdown(doc("Ordinary reference ^event-root"));
    var options = new DiaryIndex().roots(TODAY, source, -1);
    assertEquals("", options.get(0).id());
    EditorState state = new EditorState();
    state.sourcePath = TODAY;
    state.sourceSnapshot = source.text;
    state.text = "Addition";
    state.attach(options.get(0));
    assertFalse(state.rootReplacement.isEmpty());
    assertEquals("event-root", Events.rootId(state.rootReplacement));
  }

  private static final LocalDate DAY = LocalDate.of(2026, 11, 15);
  private static final String TODAY = DiaryLayout.path(DAY);
  private static final String PAST = DiaryLayout.path(DAY.minusDays(1));
  private static final String FUTURE = DiaryLayout.path(DAY.plusDays(1));
  private static final String OLDER = DiaryLayout.path(DAY.minusDays(3));
  private static final String LATER = DiaryLayout.path(DAY.plusDays(4));
  private static final String ROOT = Events.root("Root ^event-root");
  private static final Events.Parent TARGET = new Events.Parent(TODAY, "event-root");

  private static String doc(String body) {
    return "## Заметки\n\n" + body + "\n";
  }

  private static String link(String target, String id, String body) {
    return "[[" + target.replaceFirst("\\.md$", "") + "#^" + id + "|Root]]\n" + body;
  }

  private static String child(String body) {
    return link(TODAY, TARGET.id(), body);
  }

  private static final class Storage implements VaultStorage {
    final Map<String, String> files = new LinkedHashMap<>();
    final List<String> reads = new ArrayList<>();
    final List<String> listed = new ArrayList<>();
    int enumerations;
    Runnable onRead = () -> {};
    Runnable onEnumerate = () -> {};

    public String read(String path) throws IOException {
      reads.add(path);
      onRead.run();
      if (!files.containsKey(path)) throw new FileNotFoundException(path);
      return files.get(path);
    }

    public void write(String path, String text) {
      throw new AssertionError("Relation lookup must never write");
    }

    public List<String> dailyNotes() {
      enumerations++;
      onEnumerate.run();
      return listed.isEmpty() ? new ArrayList<>(files.keySet()) : listed;
    }
  }

  private static List<RelationSearch.Update> run(Storage storage, Events.Parent root)
      throws IOException {
    List<RelationSearch.Update> updates = new ArrayList<>();
    RelationSearch.run(storage, root, DAY, () -> false, updates::add);
    return updates;
  }

  private static List<RelationSearch.Related> additions(List<RelationSearch.Update> updates) {
    return updates.stream().flatMap(update -> update.additions().stream()).toList();
  }

  @Test
  public void ordinaryAndInlineReferencesDoNotStartASearch() {
    for (String text :
        List.of(
            "Ordinary note",
            "Ordinary note ^event-root",
            "[[Another page]]\nNote",
            "Text\n" + child("Addition"),
            "Note ^event-root\n> [!info]\n> ```dataviewjs\n> unrelated();\n> ```",
            "Note\n> [!info]\n> ```dataviewjs\n"
                + "> await dv.view(\"Скрипты/Dataview/outLinkInfo\", \"event-root\");\n> ```"))
      assertTrue(RelationSearch.roots(TODAY, text).isEmpty());
    assertEquals("event-root", Events.rootId(ROOT.replace("\n", "\r\n")));
  }

  @Test
  public void rootAndChildUseTheSameTargetAndResults() throws IOException {
    var storage = new Storage();
    storage.files.put(TODAY, doc(ROOT + "\n\n" + link("", TARGET.id(), "Local child")));
    storage.files.put(FUTURE, doc(child("Future child")));
    storage.files.put(PAST, doc(link(DiaryLayout.name(DAY), TARGET.id(), "Past child")));
    var own = RelationSearch.roots(TODAY, ROOT).get(0).target();
    var parent = RelationSearch.roots(PAST, child("Past child")).get(0).target();
    assertEquals(TARGET, own);
    assertEquals(own, parent);
    var updates = run(storage, own);
    assertEquals(updates, run(storage, parent));
    assertEquals("Root", updates.get(0).root().preview());
    assertEquals(
        List.of("Local child", "Past child", "Future child"),
        additions(updates).stream().map(RelationSearch.Related::preview).toList());
  }

  @Test
  public void readsExistingDaysNearestRootFirstInBothDirectionsAndOnlyOnce() throws IOException {
    var storage = new Storage();
    storage.files.put(LATER, doc(child("Later")));
    storage.files.put(FUTURE, doc(child("Tomorrow")));
    storage.files.put(TODAY, doc(ROOT + "\n\n" + child("Same day")));
    storage.files.put(OLDER, doc(child("Older")));
    storage.files.put(PAST, doc(child("Yesterday") + "\n\n" + child("Second that day")));
    storage.listed.addAll(storage.files.keySet());
    storage.listed.add(FUTURE);
    var updates = new ArrayList<RelationSearch.Update>();
    storage.onEnumerate =
        () -> {
          assertEquals(List.of(TODAY), storage.reads);
          assertEquals("Root", updates.get(0).root().preview());
          assertEquals("Same day", updates.get(0).additions().get(0).preview());
        };
    // The date the viewer came from must not affect archive order.
    RelationSearch.run(storage, TARGET, DAY.plusDays(4), () -> false, updates::add);
    assertEquals(List.of(TODAY, PAST, FUTURE, OLDER, LATER), storage.reads);
    assertEquals(1, storage.enumerations);
    assertEquals(
        List.of("Same day", "Yesterday", "Second that day", "Tomorrow", "Older", "Later"),
        additions(updates).stream().map(RelationSearch.Related::preview).toList());
    assertEquals(4, updates.get(updates.size() - 1).checked());
    assertTrue(updates.get(updates.size() - 1).complete());
  }

  @Test
  public void rootsKeepTheLocalRootFirstAndExposeOtherParentsOnceInLinkOrder() {
    String text = link(PAST, "first", "") + link(FUTURE, "second", "") + link(PAST, "first", ROOT);
    var roots = RelationSearch.roots(TODAY, text);
    assertEquals(
        List.of(TARGET, new Events.Parent(PAST, "first"), new Events.Parent(FUTURE, "second")),
        roots.stream().map(RelationSearch.RootChoice::target).toList());
  }

  @Test
  public void requiresMatchingAnchorAndPathAndIncludesNonFirstReferences() throws IOException {
    var storage = new Storage();
    storage.files.put(TODAY, doc(ROOT));
    storage.files.put(
        PAST,
        doc(
            link("", TARGET.id(), "Local to another file")
                + "\n\n"
                + link(TODAY, TARGET.id() + "-suffix", "Other id")
                + "\n\n"
                + "Inline "
                + child("Ignored")
                + "\n\n"
                + "```md\n"
                + child("Inside code")
                + "\n```"
                + "\n\n"
                + link("", "other", child("Two parents"))));
    assertEquals(
        List.of("Two parents"),
        additions(run(storage, TARGET)).stream().map(RelationSearch.Related::preview).toList());
  }

  @Test
  public void exactExternalRootPathIsResolvedBeforeTheArchive() throws IOException {
    String external = "События/Корень.md";
    Storage storage = new Storage();
    storage.files.put(external, doc(ROOT));
    storage.files.put(TODAY, doc(link(external, TARGET.id(), "Addition")));
    var updates =
        run(
            storage,
            RelationSearch.roots(TODAY, link(external, TARGET.id(), "Addition")).get(0).target());
    assertEquals(List.of(external, TODAY), storage.reads);
    assertEquals(external, updates.get(0).root().path());
    assertEquals("Addition", additions(updates).get(0).preview());
  }

  @Test
  public void missingOrUnpersistedRootDoesNotEnumerateTheArchive() throws IOException {
    Storage storage = new Storage();
    assertThrows(FileNotFoundException.class, () -> run(storage, TARGET));
    storage.files.put(TODAY, doc("Plain anchor ^event-root"));
    var updates = run(storage, TARGET);
    assertNull(updates.get(0).root());
    assertTrue(updates.get(0).complete());
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void inaccessibleArchiveFileDoesNotStopTheSearch() throws IOException {
    Storage storage = new Storage();
    storage.files.put(TODAY, doc(ROOT));
    storage.files.put(FUTURE, doc(child("Still readable")));
    storage.listed.addAll(List.of(TODAY, PAST, FUTURE));
    var updates = run(storage, TARGET);
    var result = updates.get(updates.size() - 1);
    assertTrue(result.complete());
    assertEquals(1, result.unreadable());
    assertEquals(2, result.checked());
    assertEquals("Still readable", additions(updates).get(0).preview());
  }

  @Test
  public void cancellationBeforeStartDoesNoWork() throws IOException {
    Storage storage = new Storage();
    RelationSearch.run(storage, TARGET, DAY, () -> true, result -> fail("Late result"));
    assertTrue(storage.reads.isEmpty());
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void closingAfterTheRootAvoidsArchiveEnumeration() throws IOException {
    Storage storage = new Storage();
    storage.files.put(TODAY, doc(ROOT));
    AtomicBoolean cancelled = new AtomicBoolean();
    RelationSearch.run(storage, TARGET, DAY, cancelled::get, result -> cancelled.set(true));
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void cancellationDuringArchiveReadStopsBeforeNextFileAndSuppressesLateResults()
      throws IOException {
    Storage storage = new Storage();
    storage.files.put(TODAY, doc(ROOT));
    storage.files.put(PAST, doc(child("Past")));
    storage.files.put(FUTURE, doc(child("Future")));
    AtomicBoolean cancelled = new AtomicBoolean();
    storage.onRead =
        () -> {
          if (storage.reads.size() > 1) cancelled.set(true);
        };
    List<RelationSearch.Update> updates = new ArrayList<>();
    RelationSearch.run(storage, TARGET, DAY, cancelled::get, updates::add);
    assertEquals(List.of(TODAY, PAST), storage.reads);
    assertEquals(1, updates.size());
    assertFalse(updates.get(0).complete());
  }

  @Test
  public void opensCurrentBlockAfterOffsetsChangeAndRejectsAmbiguousOrRemovedChildren() {
    String child = child("Original");
    Markdown before = new Markdown(doc(child));
    var selected = new RelationSearch.Related(PAST, before.blocks.get(0));
    Markdown shifted = new Markdown(doc("Before\n\n" + child));
    assertEquals(
        shifted.blocks.get(1), RelationSearch.currentBlock(shifted, selected).orElseThrow());
    Markdown duplicates = new Markdown(doc("Before\n\n" + child + "\n\n" + child));
    assertTrue(RelationSearch.currentBlock(duplicates, selected).isEmpty());
    assertTrue(RelationSearch.currentBlock(new Markdown(doc("New text")), selected).isEmpty());
  }

  @Test
  public void opensUpdatedRootByItsUniqueAnchor() {
    var old = new RelationSearch.Related(TODAY, new Markdown(doc(ROOT)).blocks.get(0));
    var current = new Markdown(doc("Before\n\n" + ROOT.replace("Root ^", "Edited ^")));
    assertEquals(current.blocks.get(1), RelationSearch.currentBlock(current, old).orElseThrow());
  }
}
