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
  private DraftStore drafts;
  private SafVaultStorage storage;
  private VaultRepository repository;
  private DiaryIndex index = new DiaryIndex();
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String vault = "", path = "", screen = "day", lastError;
  private LocalDate day = LocalDate.now();
  private Markdown template = new Markdown(""), document;
  private EditorState editor;
  private boolean busy = false, connected = false;
  private final Set<String> expanded = new HashSet<>(Set.of("## Заметки"));
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (connected && screen.equals("day") && !busy && document != null) refreshQuietly();
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
    drafts = new DraftStore(prefs);
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
    boolean restore = saved != null && "editor".equals(screen);
    showDay();
    if (!vault.isEmpty()) connect(vault, restore);
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.postDelayed(poll, 1000);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(poll);
    if (editor != null) drafts.save(editor, vault);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (editor != null) drafts.save(editor, vault);
    out.putString("day", day.toString());
    out.putString("screen", screen);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
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
                  lastError =
                      error.getMessage() == null
                          ? error.getClass().getSimpleName()
                          : error.getMessage();
                  ui.error(lastError);
                });
          }
        });
  }

  private record Loaded(
      SafVaultStorage storage,
      VaultRepository repository,
      DiaryIndex index,
      DiaryIndex.Opened opened) {}

  private void connect(String value, boolean restoreEditor) {
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
          r.recover();
          DiaryIndex next = new DiaryIndex();
          return new Loaded(s, r, next, next.open(s, day));
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
          if (restoreEditor && drafts.load(vault) != null) resumeDraft();
          else showDay();
        });
  }

  private void loadDay() {
    if (!connected) {
      showDay();
      return;
    }
    path = DiaryLayout.path(day);
    String requested = path;
    work(
        () -> {
          repository.recover();
          try {
            return new Markdown(storage.read(requested));
          } catch (FileNotFoundException e) {
            return null;
          }
        },
        loaded -> {
          document = loaded;
          showDay();
        });
  }

  private void refreshQuietly() {
    busy = true;
    String previous = document.text;
    String requested = path;
    io.execute(
        () -> {
          try {
            String fresh = storage.read(requested);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  busy = false;
                  if (!fresh.equals(previous) && screen.equals("day")) {
                    int y = ui.scroll.getScrollY();
                    document = new Markdown(fresh);
                    showDay();
                    ui.scroll.post(() -> ui.scroll.scrollTo(0, y));
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  busy = false;
                  ui.error(e.getMessage());
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
        drafts != null && drafts.load(vault) != null,
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
              day = day.plusDays(delta);
              loadDay();
            }
          }

          public void chooseDate() {
            if (!busy)
              new DatePickerDialog(
                      MainActivity.this,
                      (picker, y, m, d) -> {
                        day = LocalDate.of(y, m + 1, d);
                        loadDay();
                      },
                      day.getYear(),
                      day.getMonthValue() - 1,
                      day.getDayOfMonth())
                  .show();
          }

          public void today() {
            if (!busy) {
              day = LocalDate.now();
              loadDay();
            }
          }

          public void add() {
            if (!busy) startEditor(null);
          }

          public void edit(Markdown.Block block) {
            if (!busy) startEditor(block);
          }

          public void draft() {
            if (!busy) resumeDraft();
          }

          public void refresh() {
            if (!busy) loadDay();
          }
        });
    if (lastError != null) ui.error(lastError);
  }

  private void home() {
    if (editor != null) drafts.save(editor, vault);
    editor = null;
    screen = "day";
    loadDay();
  }

  private void startEditor(Markdown.Block block) {
    EditorState previous = drafts.load(vault);
    if (previous != null && !previous.text.isBlank()) {
      resumeDraft();
      ui.error("Открыт незавершённый черновик. Сохраните его или уберите через настройки.");
      return;
    }
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
    drafts.save(editor, vault);
    showEditor();
  }

  private void resumeDraft() {
    editor = drafts.load(vault);
    if (editor != null) showEditor();
  }

  private void showEditor() {
    screen = "editor";
    EditorScreen.show(
        ui,
        editor,
        new EditorScreen.Actions() {
          public void back() {
            if (!busy) home();
          }

          public void save() {
            saveEditor();
          }

          public void changed() {
            drafts.save(editor, vault);
          }

          public void target() {
            pickTarget();
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
                drafts.save(editor, vault);
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
    Markdown destination = new Markdown(editor.destinationSnapshot);
    List<Markdown.Target> targets = destination.targets(template);
    if (!editor.fresh && editor.sourcePath.equals(editor.destinationPath))
      targets =
          targets.stream()
              .filter(
                  t ->
                      t.kind() == Markdown.Kind.SECTION
                          || t.line() < editor.start
                          || t.line() >= editor.end)
              .collect(java.util.stream.Collectors.toList());
    SearchPicker.show(
        ui,
        "Раздел, задача или ключ",
        targets,
        Markdown.Target::label,
        false,
        selected -> {
          var t = selected.get(0);
          editor.chooseTarget(t);
          drafts.save(editor, vault);
          showEditor();
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
                  drafts.save(editor, vault);
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
          drafts.save(editor, vault);
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
                  drafts.save(editor, vault);
                  showEditor();
                }));
  }

  private void showRelations() {
    if (busy) return;
    String source = editor.sourcePath, text = editor.text;
    work(
        () -> index.related(storage, source, text),
        list -> {
          if (list.isEmpty()) {
            ui.error("Связанных сохранённых блоков пока нет");
            return;
          }
          SearchPicker.show(
              ui,
              "Связи",
              list,
              DiaryIndex.Related::label,
              false,
              selected ->
                  new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                      .setTitle(selected.get(0).block().section().title())
                      .setMessage(CrossReferences.clean(selected.get(0).block().text()))
                      .setPositiveButton("Закрыть", null)
                      .show());
        });
  }

  private void saveEditor() {
    if (busy || editor == null) return;
    drafts.save(editor, vault);
    Markdown from = new Markdown(editor.sourceSnapshot),
        to = new Markdown(editor.destinationSnapshot);
    final List<VaultRepository.Change> changes;
    try {
      LocalDate targetDay =
          LocalDate.parse(DiaryLayout.fileName(editor.destinationPath).substring(0, 10));
      changes =
          VaultRepository.addRoot(
              VaultRepository.plan(editor, from, to, template, targetDay, LocalDateTime.now()),
              editor);
    } catch (IllegalArgumentException e) {
      ui.error(e.getMessage());
      return;
    }
    String destination = editor.destinationPath;
    work(
        () -> {
          repository.commit(changes);
          return true;
        },
        ok -> {
          drafts.clear(vault);
          editor = null;
          day = LocalDate.parse(DiaryLayout.fileName(destination).substring(0, 10));
          screen = "day";
          loadDay();
        });
  }

  private void showSettings() {
    screen = "settings";
    String folder =
        vault.isEmpty()
            ? ""
            : android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(vault));
    SettingsScreen.show(
        ui,
        folder,
        drafts.load(vault) != null,
        repository != null,
        new SettingsScreen.Actions() {
          public void back() {
            home();
          }

          public void chooseVault() {
            MainActivity.this.chooseVault();
          }

          public void draft() {
            resumeDraft();
          }

          public void deleteDraft() {
            drafts.clear(vault);
            editor = null;
            showSettings();
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
      connect(uri.toString(), false);
    } catch (SecurityException e) {
      ui.error(e.getMessage());
    }
  }
}
