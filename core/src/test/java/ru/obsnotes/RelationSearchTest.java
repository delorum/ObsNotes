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

  private static final String TODAY = DiaryLayout.path(LocalDate.of(2026, 11, 15));
  private static final String PAST = DiaryLayout.path(LocalDate.of(2026, 11, 14));
  private static final String OLDER = DiaryLayout.path(LocalDate.of(2026, 11, 13));
  private static final String ROOT = Events.root("Root ^event-root");

  private static String doc(String body) {
    return "## Заметки\n\n" + body + "\n";
  }

  private static String link(String target, String id) {
    return "[[" + target.replaceFirst("\\.md$", "") + "#^" + id + "|Root]]\nAddition";
  }

  private static final class Storage implements VaultStorage {
    final Map<String, String> files = new LinkedHashMap<>();
    final List<String> reads = new ArrayList<>();
    boolean enumerationAllowed = false;
    int enumerations = 0;
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
      assertTrue("Unexpected archive enumeration", enumerationAllowed);
      enumerations++;
      onEnumerate.run();
      return new ArrayList<>(files.keySet());
    }
  }

  private static List<RelationSearch.Snapshot> run(Storage storage, String text, String source)
      throws IOException {
    List<RelationSearch.Snapshot> updates = new ArrayList<>();
    RelationSearch.run(storage, TODAY, text, source, () -> false, updates::add);
    return updates;
  }

  @Test
  public void unlinkedBlockDoesNotReadOrEnumerateAnything() throws IOException {
    for (String text :
        List.of(
            "Ordinary note",
            "Ordinary note ^event-root",
            "[[Another page]]\nNote",
            "Text\n" + link(PAST, "event-root"))) {
      Storage storage = new Storage();
      var updates = run(storage, text, doc(text));
      assertEquals(1, updates.size());
      assertTrue(updates.get(0).complete());
      assertTrue(updates.get(0).items().isEmpty());
      assertTrue(storage.reads.isEmpty());
      assertEquals(0, storage.enumerations);
    }
  }

  @Test
  public void anchorNeedsMatchingRelationshipCallout() {
    assertEquals("event-root", Events.rootId(ROOT));
    assertEquals("event-root", Events.rootId(ROOT.replace("\n", "\r\n")));
    assertFalse(
        Events.hasRelations(
            TODAY, "Note ^event-root\n> [!info]\n> ```dataviewjs\n> unrelated();\n> ```"));
    assertFalse(
        Events.hasRelations(
            TODAY,
            "Note\n"
                + "> [!info]\n"
                + "> ```dataviewjs\n"
                + "> await dv.view(\"Скрипты/Dataview/outLinkInfo\", \"event-root\");\n"
                + "> ```"));
  }

  @Test
  public void sameDayAdditionResolvesItsRootWithoutAnyStorageAccess() throws IOException {
    Storage storage = new Storage();
    String addition = link(TODAY, "event-root");
    var updates = run(storage, addition, doc(ROOT + "\n\n" + addition));
    var result = updates.get(updates.size() - 1);
    assertTrue(result.complete());
    assertEquals(1, result.items().size());
    assertEquals("event-root", Events.id(result.items().get(0).block().text()));
    assertTrue(storage.reads.isEmpty());
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void namedDailyRootResolvesByExactDatePathAndReadsOnlyThatFile() throws IOException {
    Storage storage = new Storage();
    storage.files.put(PAST, doc(ROOT));
    String addition = link("2026-11-14-сб", "event-root");
    var updates = run(storage, addition, doc(addition));
    assertEquals(List.of(PAST), storage.reads);
    assertEquals(0, storage.enumerations);
    assertEquals(1, updates.get(updates.size() - 1).items().size());
  }

  @Test
  public void explicitRootPathAlsoAvoidsArchiveEnumeration() throws IOException {
    Storage storage = new Storage();
    storage.files.put(PAST, doc(ROOT));
    var updates = run(storage, link(PAST, "event-root"), doc("Current note"));
    assertEquals(List.of(PAST), storage.reads);
    assertEquals(PAST, updates.get(updates.size() - 1).items().get(0).path());
  }

  @Test
  public void localWikiRootIsResolvedInItsOwnFile() throws IOException {
    Storage storage = new Storage();
    var updates = run(storage, "[[#^event-root|Root]]\nAddition", doc(ROOT));
    assertEquals(1, updates.get(updates.size() - 1).items().size());
    assertTrue(storage.reads.isEmpty());
    assertFalse(Events.references("[[#^event-root]]\nOther", PAST, TODAY, "event-root"));
  }

  @Test
  public void rootPublishesLocalChildrenBeforeArchiveAndAddsRemoteMatches() throws IOException {
    Storage storage = new Storage();
    storage.enumerationAllowed = true;
    storage.files.put(TODAY, doc(ROOT));
    storage.files.put(PAST, doc(link(TODAY, "event-root")));
    storage.files.put(OLDER, doc("Unrelated"));
    List<RelationSearch.Snapshot> updates = new ArrayList<>();
    storage.onEnumerate = () -> assertEquals(1, updates.get(0).items().size());
    RelationSearch.run(
        storage,
        TODAY,
        ROOT,
        doc(ROOT + "\n\n" + link(TODAY, "event-root")),
        () -> false,
        updates::add);
    assertEquals(List.of(PAST, OLDER), storage.reads);
    var result = updates.get(updates.size() - 1);
    assertTrue(result.complete());
    assertEquals(2, result.items().size());
    assertEquals(2, result.checked());
  }

  @Test
  public void cancellationBeforeStartDoesNoWork() throws IOException {
    Storage storage = new Storage();
    RelationSearch.run(storage, TODAY, ROOT, doc(ROOT), () -> true, result -> fail("Late result"));
    assertTrue(storage.reads.isEmpty());
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void cancellationDuringReadStopsBeforeNextFileAndSuppressesLateResults()
      throws IOException {
    Storage storage = new Storage();
    storage.enumerationAllowed = true;
    storage.files.put(PAST, doc(link(TODAY, "event-root")));
    storage.files.put(OLDER, doc(link(TODAY, "event-root")));
    AtomicBoolean cancelled = new AtomicBoolean();
    storage.onRead = () -> cancelled.set(true);
    List<RelationSearch.Snapshot> updates = new ArrayList<>();
    RelationSearch.run(storage, TODAY, ROOT, doc(ROOT), cancelled::get, updates::add);
    assertEquals(List.of(PAST), storage.reads);
    assertEquals(1, updates.size());
    assertFalse(updates.get(0).complete());
  }

  @Test
  public void closingAfterLocalResultsAvoidsArchiveEnumeration() throws IOException {
    Storage storage = new Storage();
    AtomicBoolean cancelled = new AtomicBoolean();
    RelationSearch.run(
        storage, TODAY, ROOT, doc(ROOT), cancelled::get, result -> cancelled.set(true));
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void unsavedNewRootDoesNotSearchForImpossibleBackLinks() throws IOException {
    Storage storage = new Storage();
    var updates = run(storage, ROOT, doc("Before adding an event anchor"));
    assertTrue(updates.get(updates.size() - 1).complete());
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void inaccessibleDirectParentFinishesWithAReadablePartialResult() throws IOException {
    Storage storage = new Storage();
    var updates = run(storage, link(PAST, "event-root"), doc("Addition"));
    var result = updates.get(updates.size() - 1);
    assertTrue(result.complete());
    assertEquals(1, result.unreadable());
    assertTrue(result.items().isEmpty());
    assertEquals(0, storage.enumerations);
  }

  @Test
  public void parentAndChildMatchIsNotDuplicated() throws IOException {
    Storage storage = new Storage();
    storage.enumerationAllowed = true;
    String both = link(TODAY, "event-child") + " ^event-root";
    storage.files.put(PAST, doc(both));
    String query =
        link(PAST, "event-root")
            + " ^event-child\n"
            + "> [!info]\n"
            + "> ```dataviewjs\n"
            + "> await dv.view(\"Скрипты/Dataview/outLinkInfo\", \"event-child\");\n"
            + "> ```";
    var updates = run(storage, query, doc(query));
    assertEquals(1, updates.get(updates.size() - 1).items().size());
    assertEquals(List.of(PAST), storage.reads);
  }

  @Test
  public void multipleParentsReadEachExactFileOnceAndFindSecondaryReferences() throws IOException {
    Storage storage = new Storage();
    storage.files.put(PAST, doc(ROOT + "\n\n" + Events.root("Second ^second")));
    storage.files.put(OLDER, doc(Events.root("Third ^third")));
    String text =
        "[[2026-11-14-сб#^event-root|One]]\n"
            + "[[2026-11-14-сб#^second|Two]]\n[[2026-11-13-пт#^third|Three]]\nChild";
    var updates = run(storage, text, doc(text));
    var last = updates.get(updates.size() - 1);
    assertEquals(List.of(PAST, OLDER), storage.reads);
    assertEquals(0, storage.enumerations);
    assertEquals(3, last.items().size());
    assertTrue(last.complete());
  }

  @Test
  public void rootFindsChildWhenItsLinkIsSecond() throws IOException {
    Storage storage = new Storage();
    storage.enumerationAllowed = true;
    storage.files.put(PAST, doc("[[#^other|Other]]\n" + link(TODAY, "event-root")));
    var updates = run(storage, ROOT, doc(ROOT));
    assertEquals(1, updates.get(updates.size() - 1).items().size());
  }
}
