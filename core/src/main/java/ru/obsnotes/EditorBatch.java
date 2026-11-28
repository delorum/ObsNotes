package ru.obsnotes;

import java.time.*;
import java.util.*;

/** Several noncontiguous originals share the ordinary parts editor and one verified transaction. */
public final class EditorBatch {
  public final List<EditorState> originals;

  public EditorBatch(List<EditorState> originals) {
    this.originals = List.copyOf(originals);
  }

  public static boolean selectable(Markdown.Block block, boolean work) {
    return work
        || DiaryTasks.in(block.text(), 0).isEmpty() && !FieldBlock.parse(block.text()).present();
  }

  public static EditorState create(
      String dailyPath,
      Markdown daily,
      Markdown template,
      WorkDaily.Note work,
      List<BlockSelection.Entry> selected) {
    if (selected.isEmpty()) throw new IllegalArgumentException("Выберите записи");
    List<EditorState> forms = new ArrayList<>();
    for (var item : selected) {
      Markdown source = new Markdown(item.snapshot());
      var actual =
          source.blocks.stream()
              .filter(
                  b -> b.start() == item.block().start() && b.text().equals(item.block().text()))
              .findFirst()
              .orElseThrow();
      boolean atWork = WorkDaily.isPath(item.path());
      if (!selectable(actual, atWork))
        throw new IllegalArgumentException("Выберите текстовые записи");
      EditorState form =
          atWork
              ? WorkDaily.editor(dailyPath, daily, work, actual, template)
              : EditorState.editing(dailyPath, source, template, actual);
      WorkDaily.snapshot(form, work);
      form.origins = List.of(form.partId);
      form.routeChosen = true;
      EditorRevision.remember(form, template);
      forms.add(form);
    }
    EditorState owner = forms.get(0).copy();
    owner.batch = null;
    owner.parts = new EditorParts(owner);
    owner.parts.parts.clear();
    StringBuilder text = new StringBuilder();
    for (var form : forms) {
      if (!text.isEmpty()) text.append("\n\n");
      int start = text.length();
      text.append(form.editableText());
      owner.parts.parts.add(new EditorParts.Part(start, text.length(), form.copy()));
    }
    owner.parts.value = owner.parts.baseline = text.toString();
    owner.parts.template = template;
    // The constructor imported the seed paragraph; import the batch's forms once.
    owner.parts.links.assign(new PartLinks());
    owner.parts.imported = false;
    owner.parts.importLinks();
    // Bind selected parents by stable part identity, rather than by a stale external snapshot.
    var graph = owner.parts.links;
    for (var part : owner.parts.parts) {
      List<EventLink> refs = new ArrayList<>();
      for (var ref : graph.refs(part.form().partId)) {
        var target =
            forms.stream()
                .filter(
                    f -> ref.path().equals(f.sourcePath) && ref.id().equals(Events.rootId(f.text)))
                .findFirst()
                .orElse(null);
        if (target != null) {
          refs.add(EventLink.local(target.partId));
          graph.markRoot(target.partId);
        } else refs.add(ref);
      }
      graph.set(part.form().partId, refs);
    }
    owner.batch = new EditorBatch(forms);
    owner.sensitive =
        forms.stream().anyMatch(f -> f.sensitive || f.secretOpen || SecretText.has(f.text));
    owner.cursor = 0;
    EditorRevision.remember(owner, template);
    return owner;
  }

  public List<VaultRepository.Change> plan(
      EditorState owner, Markdown template, LocalDate date, LocalDateTime now, CleanupRules rules) {
    owner.parts.validate();
    Set<String> identifiers = new HashSet<>();
    Map<String, String> versions = new HashMap<>();
    Map<String, Integer> ends = new HashMap<>();
    for (var original :
        originals.stream()
            .sorted(
                Comparator.comparing((EditorState o) -> o.sourcePath)
                    .thenComparingInt(o -> o.start))
            .toList()) {
      String previous = versions.putIfAbsent(original.sourcePath, original.sourceSnapshot);
      if (!identifiers.add(original.partId)
          || previous != null && !previous.equals(original.sourceSnapshot)
          || original.start < ends.getOrDefault(original.sourcePath, -1))
        throw new IllegalArgumentException("Исходные записи повторяются или изменились");
      ends.put(original.sourcePath, original.end);
    }
    var forms = owner.parts.savedForms().stream().map(WorkDaily::physical).toList();
    Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    before.put(owner.destinationPath, owner.destinationSnapshot);
    if (owner.workSnapshot != null)
      before.put(WorkDaily.path(WorkDaily.date(owner.destinationPath)), owner.workSnapshot);
    for (var original : originals) {
      original.originalBlock(new Markdown(original.sourceSnapshot));
      before.put(original.sourcePath, original.sourceSnapshot);
    }
    for (var form : forms) before.putIfAbsent(form.destinationPath, form.destinationSnapshot);
    Map<String, List<EditorState>> atOrigin = new LinkedHashMap<>();
    List<EditorState> insert = new ArrayList<>();
    for (var form : forms) {
      var origin =
          originals.stream().filter(o -> form.origins.contains(o.partId)).findFirst().orElse(null);
      boolean same =
          origin != null
              && form.destinationPath.equals(origin.sourcePath)
              && form.kind == Markdown.Kind.SECTION
              && form.heading.equals(
                  origin.originalBlock(new Markdown(origin.sourceSnapshot)).section().heading());
      if (same) atOrigin.computeIfAbsent(origin.partId, ignored -> new ArrayList<>()).add(form);
      else if (!form.emptyNote() || form.taskOnly()) insert.add(form);
    }
    for (var entry : before.entrySet()) {
      String value = entry.getValue();
      String newline = value.contains("\r\n") ? "\r\n" : "\n";
      var ranges =
          originals.stream()
              .filter(o -> o.sourcePath.equals(entry.getKey()))
              .sorted(Comparator.comparingInt((EditorState o) -> o.start).reversed())
              .toList();
      for (var original : ranges) {
        var group = atOrigin.getOrDefault(original.partId, List.of());
        String replacement =
            String.join(
                "\n\n",
                group.stream()
                    .map(f -> f.emptyNote() && !f.taskOnly() ? "" : f.savedText())
                    .toList());
        if (group.size() == 1
            && group.get(0).origins.size() == 1
            && EditorRevision.contents(group.get(0), template)
                .equals(EditorRevision.contents(WorkDaily.physical(original), template)))
          replacement = original.original;
        if (replacement.isBlank()
            && !Events.id(original.original).isEmpty()
            && !owner.removedRoots.contains(
                new Events.Parent(original.sourcePath, Events.id(original.original)))
            && forms.stream()
                .noneMatch(
                    f ->
                        f.origins.contains(original.partId)
                            && f.kind == Markdown.Kind.SECTION
                            && !f.emptyNote()
                            && Events.id(f.text).equals(Events.id(original.original))))
          throw new IllegalArgumentException("Сначала снимите корневую роль удаляемого блока");
        value =
            value.substring(0, original.start)
                + replacement.replace("\r\n", "\n").replace("\n", newline)
                + value.substring(original.end);
      }
      after.put(entry.getKey(), value);
    }
    for (var stored : insert) {
      EditorState form = stored.copy();
      form.parts = null;
      form.batch = null;
      form.fresh = true;
      form.initialRevision = "";
      form.sourcePath = form.destinationPath;
      var document = new Markdown(after.get(form.destinationPath));
      form.sourceSnapshot = form.destinationSnapshot = document.text;
      if (form.kind != Markdown.Kind.SECTION) {
        var targets =
            document.targets(template).stream()
                .filter(
                    t ->
                        t.kind() == form.kind
                            && t.heading().equals(form.heading)
                            && t.source().equals(form.targetSource))
                .toList();
        if (targets.size() != 1)
          throw new IllegalArgumentException("Назначение изменилось или неоднозначно");
        form.targetLine = targets.get(0).line();
      }
      for (var change :
          VaultRepository.plan(
              form,
              document,
              document,
              template,
              VaultRepository.destinationDate(form.destinationPath, date),
              now,
              CleanupRules.EMPTY)) after.put(change.path(), change.after());
    }
    boolean structural =
        !insert.isEmpty()
            || forms.size() != originals.size()
            || forms.stream().anyMatch(f -> f.origins.size() != 1 || f.emptyNote());
    List<VaultRepository.Change> changes = new ArrayList<>();
    // A destination must be verified before its original source file loses any selected block.
    Map<String, Set<String>> dependencies = new LinkedHashMap<>();
    for (var form : forms)
      for (var original : originals)
        if (form.origins.contains(original.partId)
            && !original.sourcePath.equals(form.destinationPath))
          dependencies
              .computeIfAbsent(original.sourcePath, ignored -> new LinkedHashSet<>())
              .add(form.destinationPath);
    List<String> order = new ArrayList<>();
    Set<String> done = new HashSet<>();
    for (String path : before.keySet()) visit(path, dependencies, new HashSet<>(), done, order);
    for (String path : order)
      changes.add(new VaultRepository.Change(path, before.get(path), after.get(path)));
    changes = VaultRepository.addRoots(changes, owner.parts.pendingRootEdits());
    return changes.stream()
        .map(
            c ->
                (!structural || c.before().equals(c.after()))
                    ? c
                    : new VaultRepository.Change(
                        c.path(), c.before(), DiaryCleanup.compress(c.after(), rules)))
        .toList();
  }

  private static void visit(
      String path,
      Map<String, Set<String>> dependencies,
      Set<String> active,
      Set<String> done,
      List<String> order) {
    if (done.contains(path)) return;
    if (!active.add(path))
      throw new IllegalArgumentException("Встречные переносы между файлами выполните отдельно");
    for (String target : dependencies.getOrDefault(path, Set.of()))
      visit(target, dependencies, active, done, order);
    active.remove(path);
    done.add(path);
    order.add(path);
  }
}
