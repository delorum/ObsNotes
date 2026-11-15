package ru.obsnotes;

import android.app.DatePickerDialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import ru.obsnotes.ui.*;

/** Navigation and asynchronous orchestration. Markdown rules and all form data live in core. */
public final class MainActivity extends AppCompatActivity {
  private Ui ui;
  private SharedPreferences prefs;
  private EditorRecovery recovery;
  private SafVaultStorage storage;
  private VaultRepository repository;
  private DiaryIndex index = new DiaryIndex();
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService navigationIo = Executors.newSingleThreadExecutor();
  private Future<?> navigationJob;
  private long navigationVersion;
  private boolean navigationPending = false, polling = false;
  private final ExecutorService relationIo = Executors.newSingleThreadExecutor();
  private java.util.concurrent.atomic.AtomicBoolean relationCancelled;
  private Future<?> relationJob;
  private RelationsDialog relationsDialog;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String vault = "", path = "", screen = "day", lastError;
  private LocalDate day = LocalDate.now();
  private Markdown template = new Markdown(""), document;
  private EditorState editor;
  private boolean busy = false, connected = false;
  private boolean foreground = false, conflictPending = false;
  private android.app.Dialog conflictDialog;
  private final Set<String> expanded = new HashSet<>(Set.of("## Заметки"));
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (connected
              && screen.equals("day")
              && !busy
              && !polling
              && !navigationPending
              && document != null) refreshQuietly();
          handler.postDelayed(this, 5000);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    ui = new Ui(this);
    prefs = getSharedPreferences("obsnotes", MODE_PRIVATE);
    recovery = new EditorRecovery(prefs);
    vault = prefs.getString("vault", "");
    if (saved != null) {
      day = LocalDate.parse(saved.getString("day", LocalDate.now().toString()));
      screen = saved.getString("screen", "day");
    }
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              public void handleOnBackPressed() {
                if (busy) return;
                if (!screen.equals("day")) home();
                else {
                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                  setEnabled(true);
                }
              }
            });
    showDay();
    if (!vault.isEmpty()) connect(vault);
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (conflictPending && editor != null) showConflict();
    handler.postDelayed(poll, 1000);
  }

  @Override
  protected void onPause() {
    foreground = false;
    handler.removeCallbacks(poll);
    if (editor != null
        && connected
        && screen.equals("editor")
        && !busy
        && (conflictDialog == null || !conflictDialog.isShowing())) saveEditor();
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (editor != null && !busy) recovery.save(editor, vault);
    out.putString("day", day.toString());
    out.putString("screen", screen);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    if (conflictDialog != null) conflictDialog.dismiss();
    handler.removeCallbacksAndMessages(null);
    if (relationsDialog != null) relationsDialog.dismiss();
    cancelRelations();
    relationIo.shutdownNow();
    cancelNavigation();
    navigationIo.shutdownNow();
    io.shutdown();
    super.onDestroy();
  }

  private interface Work<T> {
    T run() throws Exception;
  }

  private <T> void work(Work<T> job, Consumer<T> done) {
    if (busy) return;
    busy = true;
    ui.busy(true);
    ui.error(null);
    io.execute(
        () -> {
          try {
            T result = job.run();
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  busy = false;
                  ui.busy(false);
                  lastError = null;
                  done.accept(result);
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  busy = false;
                  ui.busy(false);
                  lastError = DayNavigation.message(error);
                  ui.error(lastError);
                  if ((error instanceof VaultRepository.Conflict || DayNavigation.missing(error))
                      && editor != null) {
                    conflictPending = true;
                    if (foreground) showConflict();
                  }
                });
          }
        });
  }

  private record Loaded(
      SafVaultStorage storage,
      VaultRepository repository,
      DiaryIndex index,
      DiaryIndex.Opened opened,
      VaultRepository.Conflict conflict) {}

  private void connect(String value) {
    cancelNavigation();
    work(
        () -> {
          SafVaultStorage s = new SafVaultStorage(this, Uri.parse(value));
          s.validate();
          String folder =
              UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toString();
          VaultRepository r =
              new VaultRepository(
                  s, new FileJournal(new File(getFilesDir(), "journals/" + folder).toPath()));
          VaultRepository.Conflict conflict = null;
          try {
            r.recover();
          } catch (VaultRepository.Conflict changed) {
            conflict = changed;
          }
          DiaryIndex next = new DiaryIndex();
          return new Loaded(s, r, next, next.open(s, day), conflict);
        },
        loaded -> {
          storage = loaded.storage;
          repository = loaded.repository;
          template = loaded.opened.template();
          document = loaded.opened.document();
          path = loaded.opened.path();
          vault = value;
          connected = true;
          index = loaded.index;
          prefs.edit().putString("vault", value).apply();
          if (recovery.load(vault) != null) restoreEditor();
          else showDay();
          if (loaded.conflict != null) {
            lastError = loaded.conflict.getMessage();
            ui.error(lastError);
            if (editor != null) {
              conflictPending = true;
              if (foreground) showConflict();
            }
          }
        });
  }

  private void cancelNavigation() {
    navigationVersion++;
    navigationPending = false;
    if (navigationJob != null) navigationJob.cancel(true);
    if (ui != null && !busy) ui.loading(false);
  }

  private void loadDay() {
    navigateTo(day, true);
  }

  private void today() {
    if (editor != null) saveEditor(() -> navigateTo(LocalDate.now(), false));
    else navigateTo(LocalDate.now(), false);
  }

  private void navigateTo(LocalDate requested, boolean refresh) {
    if (busy) return;
    cancelNavigation();
    if (!connected) {
      showDay();
      return;
    }
    if (!refresh && requested.equals(day) && document != null) {
      if (editor != null) recovery.save(editor, vault);
      editor = null;
      lastError = null;
      showDay();
      return;
    }
    if (editor != null) recovery.save(editor, vault);
    long token = navigationVersion;
    String origin = screen;
    EditorState form = editor;
    SafVaultStorage selectedStorage = storage;
    DayNavigation.View current = new DayNavigation.View(day, path, document);
    navigationPending = true;
    ui.loading(true);
    ui.error(null);
    navigationJob =
        navigationIo.submit(
            () -> {
              try {
                DayNavigation.Result result =
                    DayNavigation.open(selectedStorage, current, requested);
                runOnUiThread(
                    () -> {
                      if (isDestroyed()
                          || token != navigationVersion
                          || storage != selectedStorage
                          || !screen.equals(origin)
                          || editor != form) return;
                      navigationPending = false;
                      ui.loading(false);
                      if (!result.opened()) {
                        lastError = result.error();
                        ui.error(lastError);
                        return;
                      }
                      if (editor != null) recovery.save(editor, vault);
                      editor = null;
                      day = result.view().date();
                      path = result.view().path();
                      document = result.view().document();
                      lastError = null;
                      showDay();
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      if (!isDestroyed() && token == navigationVersion) {
                        navigationPending = false;
                        ui.loading(false);
                        lastError = DayNavigation.message(error);
                        ui.error(lastError);
                      }
                    });
              }
            });
  }

  private void refreshQuietly() {
    if (polling || busy) return;
    polling = true;
    String previous = document.text, requested = path;
    SafVaultStorage selectedStorage = storage;
    io.execute(
        () -> {
          try {
            String fresh = selectedStorage.read(requested);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  polling = false;
                  if (storage == selectedStorage
                      && screen.equals("day")
                      && path.equals(requested)
                      && document != null
                      && document.text.equals(previous)
                      && !fresh.equals(previous)) {
                    int y = ui.scroll.getScrollY();
                    document = new Markdown(fresh);
                    showDay();
                    ui.scroll.post(() -> ui.scroll.scrollTo(0, y));
                  }
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  polling = false;
                  if (storage == selectedStorage && screen.equals("day") && path.equals(requested))
                    ui.error(DayNavigation.message(error));
                });
          }
        });
  }

  private void showDay() {
    screen = "day";
    DayScreen.show(
        ui,
        day,
        document,
        template,
        connected,
        expanded,
        new DayScreen.Actions() {
          public void settings() {
            if (!busy) showSettings();
          }

          public void pickVault() {
            chooseVault();
          }

          public void navigate(int delta) {
            if (!busy) {
              navigateTo(day.plusDays(delta), false);
            }
          }

          public void chooseDate() {
            if (!busy)
              new DatePickerDialog(
                      MainActivity.this,
                      (picker, y, m, d) -> {
                        navigateTo(LocalDate.of(y, m + 1, d), false);
                      },
                      day.getYear(),
                      day.getMonthValue() - 1,
                      day.getDayOfMonth())
                  .show();
          }

          public void today() {
            if (!busy) {
              MainActivity.this.today();
            }
          }

          public void add() {
            if (!busy) startEditor(null);
          }

          public void edit(Markdown.Block block) {
            if (!busy) startEditor(block);
          }

          public void refresh() {
            if (!busy) loadDay();
          }
        });
    if (lastError != null) ui.error(lastError);
  }

  private void home() {
    if (editor != null) {
      saveEditor();
      return;
    }
    cancelNavigation();
    if (editor != null) recovery.save(editor, vault);
    editor = null;
    showDay();
    if (connected && document != null && !polling) refreshQuietly();
  }

  private void startEditor(Markdown.Block block) {
    cancelNavigation();
    if (document == null) return;
    editor = new EditorState();
    editor.sourcePath = path;
    editor.destinationPath = path;
    editor.sourceSnapshot = document.text;
    editor.destinationSnapshot = document.text;
    if (block != null) {
      editor.fresh = false;
      editor.original = block.text();
      editor.text = block.text();
      editor.start = block.start();
      editor.end = block.end();
      editor.heading = block.section().heading();
    }
    recovery.save(editor, vault);
    showEditor();
  }

  private void restoreEditor() {
    cancelNavigation();
    editor = recovery.load(vault);
    if (editor != null) showEditor();
  }

  private void showEditor() {
    cancelNavigation();
    editor.initializeRoutes(template);
    screen = "editor";
    EditorScreen.show(
        ui,
        editor,
        template,
        new EditorScreen.Actions() {
          public void back() {
            if (!busy) home();
          }

          public void save() {
            saveEditor();
          }

          public void changed() {
            recovery.save(editor, vault);
          }

          public void sections() {
            pickTarget();
          }

          public void routesChanged() {
            recovery.save(editor, vault);
            showEditor();
          }

          public void replaceRoute(int index) {
            pickRouteReplacement(index);
          }

          public void replaceTag(int index) {
            pickTagReplacement(index);
          }

          public void today() {
            MainActivity.this.today();
          }

          public void destination() {
            pickDestination();
          }

          public void tags(boolean oneLine, boolean person, int cursor) {
            pickTags(oneLine, person, cursor);
          }

          public void root() {
            if (!busy) {
              try {
                editor.text = Events.root(editor.text);
                recovery.save(editor, vault);
                showEditor();
              } catch (IllegalArgumentException e) {
                ui.error(e.getMessage());
              }
            }
          }

          public void link() {
            pickRoot();
          }

          public void related() {
            showRelations();
          }
        });
  }

  private void pickTarget() {
    if (busy) return;
    var catalog = editor.routingCatalog(template);
    List<Routing.Choice> choices = routeChoices(catalog);
    SearchPicker.show(
        ui,
        "Разделы",
        choices,
        catalog::searchLabel,
        true,
        editor.pickerRoutes(),
        editor::validateRoutes,
        selected -> {
          try {
            editor.applyRoutes(selected, catalog);
            recovery.save(editor, vault);
            showEditor();
          } catch (IllegalArgumentException error) {
            ui.error(error.getMessage());
          }
        });
  }

  private List<Routing.Choice> routeChoices(Routing.Catalog catalog) {
    return catalog.choices.stream()
        .filter(c -> !editor.editFields || c.target().kind() == Markdown.Kind.SECTION)
        .filter(
            c ->
                editor.fresh
                    || !editor.sourcePath.equals(editor.destinationPath)
                    || c.target().kind() == Markdown.Kind.SECTION
                    || c.target().line() < editor.start
                    || c.target().line() >= editor.end)
        .collect(java.util.stream.Collectors.toList());
  }

  private void pickRouteReplacement(int index) {
    if (busy) return;
    var catalog = editor.routingCatalog(template);
    var current = editor.routes.get(index);
    var choices =
        routeChoices(catalog).stream()
            .filter(c -> c.equals(current) || !editor.routes.contains(c))
            .filter(
                c ->
                    index == 0
                        || c.target().kind() == Markdown.Kind.SECTION && !c.crossId().isEmpty())
            .collect(java.util.stream.Collectors.toList());
    SearchPicker.show(
        ui,
        index == 0 ? "Основной раздел" : "Дополнительный раздел",
        choices,
        catalog::searchLabel,
        false,
        List.of(current),
        selected -> editor.validateRoutes(editor.replacingRoute(index, selected.get(0))),
        selected -> {
          editor.replaceRoute(index, selected.get(0), catalog);
          recovery.save(editor, vault);
          showEditor();
        });
  }

  private void pickTagReplacement(int index) {
    if (busy) return;
    var catalog = editor.routingCatalog(template);
    var tag = editor.tags().get(index);
    var options = TagChoices.of(template, tag.person(), editor.unmanagedCrossIds(tag, catalog));
    SearchPicker.show(
        ui,
        "Заменить метку",
        options.items(),
        d -> d.id() + " · " + d.title(),
        !tag.person(),
        options.selected(),
        ignored -> null,
        true,
        selected -> {
          try {
            editor.replaceTag(
                index,
                selected.stream()
                    .map(CrossReferences.Destination::id)
                    .collect(java.util.stream.Collectors.toList()),
                catalog);
            recovery.save(editor, vault);
            showEditor();
          } catch (IllegalArgumentException error) {
            ui.error(error.getMessage());
          }
        });
  }

  private void pickDestination() {
    if (busy) return;
    LocalDate source = LocalDate.parse(DiaryLayout.fileName(editor.sourcePath).substring(0, 10));
    SafVaultStorage chosenStorage = storage;
    PastDayPicker.show(
        ui,
        source,
        (from, loaded, failed) ->
            io.execute(
                () -> {
                  try {
                    PastDays.Page page = PastDays.load(chosenStorage, from);
                    runOnUiThread(
                        () -> {
                          if (!isDestroyed()) loaded.accept(page);
                        });
                  } catch (Exception error) {
                    runOnUiThread(
                        () -> {
                          if (!isDestroyed())
                            failed.accept(
                                error.getMessage() == null
                                    ? "Не удалось загрузить дни"
                                    : error.getMessage());
                        });
                  }
                }),
        selected ->
            work(
                () -> index.day(storage, selected.date()),
                loaded -> {
                  editor.chooseDay(loaded.path(), loaded.document().text);
                  recovery.save(editor, vault);
                  showEditor();
                }));
  }

  private void pickTags(boolean oneLine, boolean person, int cursor) {
    if (busy) return;
    SearchPicker.show(
        ui,
        oneLine ? "Показать строку в разделах" : "Показать блок в разделах",
        CrossReferences.selectable(template, person),
        d -> d.id() + " · " + d.title(),
        true,
        selected -> {
          editor.addTags(
              cursor,
              selected.stream()
                  .map(CrossReferences.Destination::id)
                  .collect(java.util.stream.Collectors.toList()),
              oneLine,
              person);
          recovery.save(editor, vault);
          showEditor();
        });
  }

  private List<Events.Root> roots() throws IOException {
    Markdown current = index.read(storage, editor.sourcePath);
    return index.roots(editor.sourcePath, current, editor.fresh ? -1 : editor.start);
  }

  private void pickRoot() {
    if (busy) return;
    work(
        this::roots,
        list ->
            SearchPicker.show(
                ui,
                "Связать с блоком",
                list,
                r -> (r.id().isEmpty() ? "Создать корневой · " : "") + r.label(),
                false,
                selected -> {
                  try {
                    editor.attach(selected.get(0));
                  } catch (IllegalArgumentException e) {
                    ui.error(e.getMessage());
                    return;
                  }
                  recovery.save(editor, vault);
                  showEditor();
                }));
  }

  private void cancelRelations() {
    if (relationCancelled != null) relationCancelled.set(true);
    if (relationJob != null) relationJob.cancel(true);
  }

  private void showRelations() {
    if (busy || editor == null) return;
    String source = editor.sourcePath, text = editor.text, snapshot = editor.sourceSnapshot;
    if (!Events.hasRelations(source, text)) {
      Toast.makeText(this, "У блока нет связей", Toast.LENGTH_SHORT).show();
      return;
    }
    if (relationsDialog != null) relationsDialog.dismiss();
    cancelRelations();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    relationCancelled = cancelled;
    var dialog =
        new RelationsDialog(
            ui,
            () -> {
              cancelled.set(true);
              if (relationCancelled == cancelled && relationJob != null) relationJob.cancel(true);
            },
            selected ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(selected.block().section().title())
                    .setMessage(CrossReferences.clean(selected.block().text()))
                    .setPositiveButton("Закрыть", null)
                    .show());
    relationsDialog = dialog;
    SafVaultStorage selectedStorage = storage;
    relationJob =
        relationIo.submit(
            () -> {
              try {
                RelationSearch.run(
                    selectedStorage,
                    source,
                    text,
                    snapshot,
                    () -> cancelled.get() || Thread.currentThread().isInterrupted(),
                    result ->
                        runOnUiThread(
                            () -> {
                              if (!isDestroyed() && !cancelled.get()) dialog.update(result);
                            }));
              } catch (Exception error) {
                if (!cancelled.get())
                  runOnUiThread(
                      () -> {
                        if (!isDestroyed() && !cancelled.get())
                          dialog.failed(
                              "Поиск не завершён: "
                                  + (error.getMessage() == null
                                      ? "файл недоступен"
                                      : error.getMessage()));
                      });
              }
            });
  }

  private void saveEditor() {
    saveEditor(null);
  }

  private void showConflict() {
    if (editor == null || isDestroyed() || conflictDialog != null && conflictDialog.isShowing())
      return;
    conflictPending = false;
    conflictDialog =
        ConflictDialog.show(ui, !editor.conflictCopyText().isBlank(), this::resolveConflict);
  }

  private void resolveConflict(boolean copy) {
    if (busy || editor == null) return;
    EditorState form = editor;
    String selectedVault = vault;
    work(
        () -> {
          var result =
              ConflictResolution.resolve(
                  repository, storage, form, template, copy, LocalDateTime.now());
          recovery.clear(selectedVault);
          return result;
        },
        result -> {
          editor = null;
          conflictPending = false;
          path = result.path();
          day = LocalDate.parse(DiaryLayout.fileName(path).substring(0, 10));
          document = result.document();
          showDay();
        });
  }

  private void saveEditor(Runnable after) {
    if (busy || editor == null) return;
    cancelNavigation();
    recovery.save(editor, vault);
    EditorState form = editor;
    String selectedVault = vault;
    Markdown from = new Markdown(form.sourceSnapshot), to = new Markdown(form.destinationSnapshot);
    final List<VaultRepository.Change> changes;
    try {
      LocalDate targetDay =
          LocalDate.parse(DiaryLayout.fileName(form.destinationPath).substring(0, 10));
      changes = VaultRepository.plan(form, from, to, template, targetDay, LocalDateTime.now());
    } catch (IllegalArgumentException error) {
      lastError = error.getMessage();
      ui.error(lastError);
      return;
    }
    if (changes.isEmpty()) {
      recovery.clear(selectedVault);
      editor = null;
      lastError = null;
      showDay();
      if (after != null) after.run();
      return;
    }
    var result =
        changes.stream()
            .filter(c -> c.path().equals(form.destinationPath))
            .findFirst()
            .orElse(changes.get(0));
    VaultRepository selectedRepository = repository;
    work(
        () -> {
          selectedRepository.commit(changes);
          recovery.clear(selectedVault);
          return true;
        },
        ok -> {
          editor = null;
          day = LocalDate.parse(DiaryLayout.fileName(result.path()).substring(0, 10));
          path = result.path();
          document = new Markdown(result.after());
          showDay();
          if (after != null) after.run();
        });
  }

  private void showSettings() {
    cancelNavigation();
    screen = "settings";
    String folder =
        vault.isEmpty()
            ? ""
            : android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(vault));
    SettingsScreen.show(
        ui,
        folder,
        repository != null,
        new SettingsScreen.Actions() {
          public void back() {
            home();
          }

          public void chooseVault() {
            MainActivity.this.chooseVault();
          }

          public void recover() {
            work(
                () -> {
                  repository.recover();
                  return true;
                },
                ok -> home());
          }

          public void keepFiles() {
            work(
                () -> {
                  repository.keepCurrentFiles();
                  return true;
                },
                ok -> home());
          }
        });
    if (lastError != null) ui.error(lastError);
  }

  private void chooseVault() {
    if (busy) return;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    startActivityForResult(intent, 10);
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 10 || result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    int flags =
        data.getFlags()
            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    try {
      if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        throw new SecurityException("Нужно разрешить чтение и запись");
      getContentResolver()
          .takePersistableUriPermission(
              uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      connect(uri.toString());
    } catch (SecurityException e) {
      ui.error(e.getMessage());
    }
  }
}
