package ru.obsnotes;

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
  private UsageHistory usage;
  private SectionVisibilityPrefs sectionVisibility;
  private PasswordStore passwords;
  private final Runnable expirePassword =
      () -> {
        if (passwords != null) passwords.close();
      };
  private SafVaultStorage storage;
  private VaultRepository repository;
  private DiaryIndex index = new DiaryIndex();
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService navigationIo = Executors.newSingleThreadExecutor();
  private Future<?> navigationJob;
  private final ExecutorService availabilityIo = Executors.newSingleThreadExecutor();
  private Future<?> availabilityJob;
  private final ExecutorService calendarIo = Executors.newSingleThreadExecutor();
  private android.app.Dialog dateCalendar;
  private com.google.android.material.button.MaterialButton nextDayButton;
  private final NextDayAvailability nextDays = new NextDayAvailability();
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
  private DayContentRules contentRules = DayContentRules.EMPTY;
  private EditorState editor;

  private record TaskViewport(String path, int line, int y) {}

  private TaskViewport taskViewport;
  private boolean busy = false, connected = false;
  private volatile boolean destroying;
  private boolean foreground = false, conflictPending = false;
  private android.app.Dialog conflictDialog;
  private String sectionQuery = "";
  private final ExecutorService weatherIo = Executors.newSingleThreadExecutor();
  private Future<?> weatherJob;
  private long weatherVersion;
  private boolean weatherLoading;
  private String checklistPath = "", checklistText = "";
  private DailyChecklist.Context checklistContext;
  private final Set<String> expanded = new HashSet<>(Set.of("## Заметки"));
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (connected
              && screen.equals("day")
              && !busy
              && !polling
              && !navigationPending
              && document != null) {
            checkNextDay();
            refreshQuietly();
          }
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
    usage = new UsageHistory(prefs);
    sectionVisibility = new SectionVisibilityPrefs(prefs);
    passwords = new PasswordStore(prefs);
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
                if (screen.equals("section-visibility")) showSettings();
                else if (!screen.equals("day")) home();
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
    if (dateCalendar != null) dateCalendar.dismiss();
    if (editor != null
        && connected
        && screen.equals("editor")
        && !busy
        && (conflictDialog == null || !conflictDialog.isShowing())) {
      if (editor.sensitive || SecretText.has(editor.original)) recovery.save(editor, vault);
      else saveEditor();
    }
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
    destroying = true;
    cancelWeather();
    passwords.close();
    weatherIo.shutdownNow();
    if (conflictDialog != null) conflictDialog.dismiss();
    handler.removeCallbacksAndMessages(null);
    if (relationsDialog != null) relationsDialog.dismiss();
    cancelRelations();
    relationIo.shutdownNow();
    cancelNavigation();
    navigationIo.shutdownNow();
    availabilityIo.shutdownNow();
    if (dateCalendar != null) dateCalendar.dismiss();
    calendarIo.shutdownNow();
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
                  updateNextDayButton();
                  lastError = null;
                  done.accept(result);
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  busy = false;
                  ui.busy(false);
                  updateNextDayButton();
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
      DayContentRules rules,
      VaultRepository.Conflict conflict) {}

  private void connect(String value) {
    if (dateCalendar != null) dateCalendar.dismiss();
    nextDays.clear();
    passwords.close();
    handler.removeCallbacks(expirePassword);
    cancelWeather();
    checklistContext = null;
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
          return new Loaded(s, r, next, next.open(s, day), DayContentRules.load(s), conflict);
        },
        loaded -> {
          storage = loaded.storage;
          repository = loaded.repository;
          template = loaded.opened.template();
          contentRules = loaded.rules;
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
    checklistContext = null;
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
                DayContentRules rules =
                    result.opened() ? DayContentRules.load(selectedStorage) : null;
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
                      contentRules = rules;
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
                    document = new Markdown(fresh);
                    showDayKeepingPosition();
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
    nextDayButton =
        DayScreen.show(
            ui,
            day,
            document,
            template,
            contentRules,
            hiddenSections(document),
            connected,
            nextDayIsAvailable(),
            expanded,
            sectionQuery,
            new DayScreen.Actions() {
              public void settings() {
                if (!busy) showSettings();
              }

              public void pickVault() {
                chooseVault();
              }

              public void navigate(int delta) {
                if (!busy && (delta <= 0 || nextDayIsAvailable())) {
                  navigateTo(day.plusDays(delta), false);
                }
              }

              public void chooseDate() {
                showDateCalendar();
              }

              public void today() {
                if (!busy) {
                  MainActivity.this.today();
                }
              }

              public void add(String heading) {
                if (!busy) startEditor(null, heading);
              }

              public void edit(Markdown.Block block) {
                if (!busy) startEditor(block, block.section().heading());
              }

              public void refresh() {
                if (!busy) loadDay();
              }

              public void searchChanged(String value) {
                sectionQuery = value;
              }

              public void delete(Markdown.Block block, boolean projection) {
                deleteBlock(block, projection);
              }

              public void toggle(DiaryTasks.Task task) {
                toggleTask(task);
              }

              public void appendTask(DiaryTasks.Task task) {
                if (busy || document == null) return;
                cancelWeather();
                cancelNavigation();
                try {
                  editor = EditorState.editTask(path, document, template, task);
                  taskViewport = new TaskViewport(path, task.start(), ui.scroll.getScrollY());
                  recovery.save(editor, vault);
                  showEditor();
                } catch (IllegalArgumentException error) {
                  ui.error(error.getMessage());
                }
              }

              public void weather() {
                startWeather();
              }

              public void checklist(
                  Consumer<List<DailyChecklist.Row>> loaded, Consumer<String> failed) {
                loadChecklist(loaded, failed);
              }
            });
    if (lastError != null) ui.error(lastError);
    checkNextDay();
  }

  private void showDateCalendar() {
    if (busy || !connected || dateCalendar != null && dateCalendar.isShowing()) return;
    SafVaultStorage selectedStorage = storage;
    DayContentRules rules = contentRules;
    CalendarDays.Day current =
        document == null ? null : new CalendarDays.Day(day, CalendarDays.assess(document, rules));
    dateCalendar =
        DayCalendarDialog.show(
            ui,
            day,
            current,
            (month, known, loaded, failed) -> {
              var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
              Future<?> job =
                  calendarIo.submit(
                      () -> {
                        try {
                          CalendarDays.load(
                              selectedStorage,
                              month,
                              rules,
                              known,
                              () -> cancelled.get() || Thread.currentThread().isInterrupted(),
                              entry ->
                                  runOnUiThread(
                                      () -> {
                                        if (!isDestroyed() && !cancelled.get())
                                          loaded.accept(entry);
                                      }));
                        } catch (Exception error) {
                          if (!cancelled.get())
                            runOnUiThread(
                                () -> {
                                  if (!isDestroyed() && !cancelled.get())
                                    failed.accept(DayNavigation.message(error));
                                });
                        }
                      });
              return () -> {
                cancelled.set(true);
                job.cancel(true);
              };
            },
            selected -> navigateTo(selected, false));
  }

  private boolean nextDayIsAvailable() {
    return connected && nextDays.available(vault, day);
  }

  private void updateNextDayButton() {
    if (screen.equals("day") && nextDayButton != null)
      DayScreen.setNextEnabled(nextDayButton, !busy && nextDayIsAvailable());
  }

  private void checkNextDay() {
    if (!connected || !screen.equals("day")) return;
    var request = nextDays.request(vault, day, SystemClock.elapsedRealtime());
    if (request == null) return;
    if (availabilityJob != null) availabilityJob.cancel(true);
    SafVaultStorage selectedStorage = storage;
    updateNextDayButton();
    availabilityJob =
        availabilityIo.submit(
            () -> {
              boolean available;
              try {
                available = DayNavigation.hasNext(selectedStorage, request.date());
              } catch (Exception error) {
                // A failed background probe never blocks navigation or changes the displayed note.
                available = false;
              }
              boolean result = available;
              runOnUiThread(
                  () -> {
                    if (isDestroyed() || storage != selectedStorage) return;
                    if (nextDays.complete(request, result, SystemClock.elapsedRealtime()))
                      updateNextDayButton();
                  });
            });
  }

  private void showDayKeepingPosition() {
    showDayAt(ui.scroll.getScrollY());
  }

  private void showDayAt(int y) {
    showDay();
    var scroll = ui.scroll;
    ui.afterLayout(scroll, () -> scroll.scrollTo(0, y));
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

  private void startEditor(Markdown.Block block, String heading) {
    taskViewport = null;
    cancelWeather();
    cancelNavigation();
    if (document == null) return;
    try {
      editor =
          block == null
              ? EditorState.newNote(path, document, template, heading)
              : EditorState.editing(path, document, template, block);
    } catch (IllegalArgumentException error) {
      ui.error(error.getMessage());
      return;
    }
    recovery.save(editor, vault);
    showEditor();
  }

  private void restoreEditor() {
    cancelWeather();
    cancelNavigation();
    editor = recovery.load(vault);
    if (editor != null) showEditor();
  }

  private void showEditor() {
    cancelNavigation();
    editor.initializeRoutes(template);
    editor.enableParts(template);
    if (editor.parts != null)
      for (var part : editor.parts.parts) part.form().initializeRoutes(template);
    screen = "editor";
    EditorScreen.show(
        ui,
        editor,
        template,
        prefs.getString("weatherCity", "vidnoe"),
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

          public void part(int index) {
            if (editor.parts != null) editor.parts.selected = index;
          }

          public void sections() {
            pickTarget();
          }

          public void routesChanged() {
            recovery.save(editor, vault);
            showEditor();
          }

          public void valueTarget() {
            pickRouteReplacement(0, true);
          }

          public void replaceRoute(int index) {
            pickRouteReplacement(index);
          }

          public void replaceTag(int index) {
            pickTagReplacement(index);
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
                if (editor.parts != null) editor.parts.makeRoot(editor.parts.selected);
                else {
                  editor.restoreRootMetadata();
                  editor.text = Events.root(editor.text);
                }
                recovery.save(editor, vault);
                showEditor();
              } catch (IllegalArgumentException e) {
                ui.error(e.getMessage());
              }
            }
          }

          public void link() {
            pickRoot(null);
          }

          public void editLink(String key) {
            pickRoot(key);
          }

          public void deleteRoot() {
            if (busy) return;
            try {
              if (editor.parts != null) editor.parts.deleteRoot(editor.parts.selected);
              else editor.deleteRootMetadata();
              recovery.save(editor, vault);
              showEditor();
            } catch (IllegalArgumentException error) {
              ui.error(error.getMessage());
            }
          }

          public void related() {
            showRelations();
          }

          public void secret() {
            toggleSecret();
          }

          public void weatherLocation() {
            chooseWeatherLocation();
          }

          public void fetchWeather() {
            loadWeather();
          }
        });
    ui.loading(weatherLoading);
  }

  private void pickTarget() {
    if (busy) return;
    EditorState form = editor.activePart();
    var catalog = form.routingCatalog(template);
    List<Routing.Choice> choices = routeChoices(catalog);
    SearchPicker.show(
        ui,
        "Разделы",
        choices,
        catalog::pickerLabel,
        true,
        form.pickerRoutes(),
        choicesToCheck -> validatePartRoutes(form, choicesToCheck),
        catalog::searchLabel,
        selected -> {
          try {
            form.applyRoutes(selected, catalog);
            recovery.save(editor, vault);
            showEditor();
          } catch (IllegalArgumentException error) {
            ui.error(error.getMessage());
          }
        });
  }

  private String validatePartRoutes(EditorState form, List<Routing.Choice> choices) {
    return editor.parts == null
        ? form.validateRoutes(choices)
        : editor.parts.validateRoutes(form, choices);
  }

  private List<Routing.Choice> routeChoices(Routing.Catalog catalog) {
    return routeChoices(catalog, false);
  }

  private List<Routing.Choice> routeChoices(Routing.Catalog catalog, boolean detach) {
    EditorState form = editor.activePart();
    var visibility = hiddenFor(form);
    return usage
        .load(vault)
        .rankedRoutes(
            catalog.choices.stream()
                .filter(c -> !visibility.hidden(c.target().heading()))
                .filter(
                    c ->
                        detach
                            || !form.editFields && !form.valueBound()
                            || c.target().kind() == Markdown.Kind.SECTION)
                .filter(
                    c ->
                        form.fresh
                            || !form.sourcePath.equals(form.destinationPath)
                            || c.target().kind() == Markdown.Kind.SECTION
                            || c.target().line() < form.start
                            || c.target().line() >= form.end)
                .collect(java.util.stream.Collectors.toList()));
  }

  private void pickRouteReplacement(int index) {
    pickRouteReplacement(index, false);
  }

  private void pickRouteReplacement(int index, boolean detach) {
    if (busy) return;
    EditorState form = editor.activePart();
    var catalog = form.routingCatalog(template);
    var current = form.routes.isEmpty() ? catalog.resolve(form.target()) : form.routes.get(index);
    var choices =
        routeChoices(catalog, detach).stream()
            .filter(c -> c.equals(current) || !form.routes.contains(c))
            .filter(
                c ->
                    index == 0
                        || c.target().kind() == Markdown.Kind.SECTION && !c.crossId().isEmpty())
            .collect(java.util.stream.Collectors.toList());
    SearchPicker.show(
        ui,
        detach ? "Назначение значения" : index == 0 ? "Основной раздел" : "Дополнительный раздел",
        choices,
        catalog::pickerLabel,
        false,
        List.of(current),
        selected -> {
          EditorState candidate = form.copy();
          if (detach) candidate.removeValueTarget();
          return validatePartRoutes(candidate, candidate.replacingRoute(index, selected.get(0)));
        },
        catalog::searchLabel,
        selected -> {
          if (detach) form.removeValueTarget();
          form.replaceRoute(index, selected.get(0), catalog);
          recovery.save(editor, vault);
          showEditor();
        });
  }

  private void pickTagReplacement(int index) {
    if (busy) return;
    EditorState form = editor.activePart();
    var catalog = form.routingCatalog(template);
    var tag = form.tags().get(index);
    var options = TagChoices.of(template, tag.person(), form.unmanagedCrossIds(tag, catalog));
    SearchPicker.show(
        ui,
        "Заменить метку",
        usage
            .load(vault)
            .ranked(
                hiddenFor(form).offered(options.items(), CrossReferences.Destination::heading),
                CrossReferences.Destination::heading),
        d -> d.id() + " · " + d.title(),
        !tag.person(),
        options.selected(),
        ignored -> null,
        true,
        catalog::tagSearchLabel,
        selected -> {
          try {
            form.replaceTag(
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
    stopWeatherInEditor();
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
                () -> {
                  try {
                    return index.day(storage, selected.date());
                  } catch (java.io.FileNotFoundException absent) {
                    return null;
                  }
                },
                loaded -> {
                  if (loaded == null) {
                    lastError = "Заметка не найдена";
                    ui.error(lastError);
                    return;
                  }
                  editor.chooseDay(loaded.path(), loaded.document().text);
                  recovery.save(editor, vault);
                  showEditor();
                }));
  }

  private void pickTags(boolean oneLine, boolean person, int cursor) {
    if (busy) return;
    EditorState form = editor.activePart();
    var catalog = form.routingCatalog(template);
    SearchPicker.show(
        ui,
        oneLine ? "Показать строку в разделах" : "Показать блок в разделах",
        usage
            .load(vault)
            .ranked(
                hiddenFor(form)
                    .offered(
                        CrossReferences.selectable(template, person),
                        CrossReferences.Destination::heading),
                CrossReferences.Destination::heading),
        d -> d.id() + " · " + d.title(),
        true,
        List.of(),
        ignored -> null,
        catalog::tagSearchLabel,
        selected -> {
          form.addTags(
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

  private record RootSelection(Events.Root root, String snapshot) {}

  private void pickRoot(String replacing) {
    if (busy) return;
    EditorState form = editor.activePart();
    int part = editor.parts == null ? -1 : editor.parts.selected;
    work(
        () -> index.roots(storage, form, contentRules),
        list ->
            SearchPicker.show(
                ui,
                replacing == null ? "Добавить связь с корнем" : "Изменить связь",
                list,
                root ->
                    (root.id().isEmpty() ? "Создать корневой · " : "") + root.label(contentRules),
                false,
                selected ->
                    work(
                        () -> {
                          Events.Root root = selected.get(0);
                          return new RootSelection(root, index.rootSnapshot(storage, form, root));
                        },
                        choice -> {
                          try {
                            if (editor.parts != null)
                              editor.parts.addExternal(
                                  part, choice.root(), choice.snapshot(), replacing);
                            else
                              form.addRootLink(
                                  choice.root(),
                                  choice.snapshot(),
                                  replacing == null
                                      ? null
                                      : Integer.parseInt(replacing.substring(4)));
                            recovery.save(editor, vault);
                            showEditor();
                          } catch (IllegalArgumentException error) {
                            ui.error(error.getMessage());
                          }
                        })));
  }

  private void cancelRelations() {
    if (relationCancelled != null) relationCancelled.set(true);
    if (relationJob != null) relationJob.cancel(true);
  }

  private void showRelations() {
    if (busy || editor == null) return;
    String source = editor.sourcePath,
        text = editor.activePart().text,
        snapshot = editor.sourceSnapshot;
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
    cancelWeather();
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
          taskViewport = null;
          path = result.path();
          day = LocalDate.parse(DiaryLayout.fileName(path).substring(0, 10));
          document = result.document();
          showDay();
        });
  }

  private void saveEditor(Runnable after) {
    if (busy || editor == null) return;
    stopWeatherInEditor();
    cancelNavigation();
    if (editor.parts != null) editor.parts.sync();
    EditorState form = editor;
    int returnY =
        taskViewport != null
                && taskViewport.path().equals(form.destinationPath)
                && (taskViewport.line() == form.targetLine || taskViewport.line() == form.start)
            ? taskViewport.y()
            : -1;
    if (EditorRevision.unchanged(form, template)) {
      recovery.clear(vault);
      editor = null;
      conflictPending = false;
      lastError = null;
      taskViewport = null;
      if (returnY >= 0) showDayAt(returnY);
      else showDay();
      if (after != null) after.run();
      return;
    }
    recovery.save(editor, vault);
    String selectedVault = vault;
    Markdown from = new Markdown(form.sourceSnapshot), to = new Markdown(form.destinationSnapshot);
    SafVaultStorage selectedStorage = storage;
    Markdown selectedTemplate = template;
    VaultRepository selectedRepository = repository;
    work(
        () -> {
          LocalDate targetDay =
              LocalDate.parse(DiaryLayout.fileName(form.destinationPath).substring(0, 10));
          var changes =
              VaultRepository.plan(
                  form,
                  from,
                  to,
                  selectedTemplate,
                  targetDay,
                  LocalDateTime.now(),
                  CleanupRules.load(selectedStorage));
          var uses = SectionUsage.planned(form, selectedTemplate, changes);
          selectedRepository.commit(changes);
          usage.record(selectedVault, uses);
          recovery.clear(selectedVault);
          return changes;
        },
        changes -> {
          editor = null;
          if (!changes.isEmpty()) {
            if (returnY < 0) checklistContext = null;
            var result =
                changes.stream()
                    .filter(c -> c.path().equals(form.destinationPath))
                    .findFirst()
                    .orElse(changes.get(0));
            day = LocalDate.parse(DiaryLayout.fileName(result.path()).substring(0, 10));
            path = result.path();
            document = new Markdown(result.after());
          }
          taskViewport = null;
          if (returnY >= 0) showDayAt(returnY);
          else showDay();
          if (after != null) after.run();
        });
  }

  private void applyDayChange(String selectedPath, Markdown before, Work<Markdown> update) {
    if (busy) return;
    VaultRepository selectedRepository = repository;
    work(
        () -> {
          Markdown after = update.run();
          selectedRepository.commit(DayEdits.change(selectedPath, before, after));
          return after;
        },
        after -> {
          if (path.equals(selectedPath)) document = after;
          showDayKeepingPosition();
        });
  }

  private void deleteBlock(Markdown.Block block, boolean projection) {
    if (busy || document == null) return;
    Markdown before = document;
    String selectedPath = path;
    SafVaultStorage selectedStorage = storage;
    String message =
        (projection
                ? "Удалится оригинал из раздела «"
                    + block.section().title()
                    + "» и его отображения.\n\n"
                : "")
            + CrossReferences.clean(block.text());
    if (message.length() > 220) message = message.substring(0, 220) + "…";
    if (!Events.rootId(block.text()).isEmpty())
      message += "\n\nСвязи с этим корнем перестанут открываться.";
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("Удалить запись?")
        .setMessage(message)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Удалить",
            (d, w) -> {
              applyDayChange(
                  selectedPath,
                  before,
                  () -> DayEdits.delete(before, block, CleanupRules.load(selectedStorage)));
            })
        .show();
  }

  private void toggleTask(DiaryTasks.Task task) {
    if (busy || document == null) return;
    Markdown before = document;
    LocalDate selectedDay = day;
    applyDayChange(path, before, () -> DiaryTasks.toggle(before, task, selectedDay));
  }

  private void loadChecklist(Consumer<List<DailyChecklist.Row>> loaded, Consumer<String> failed) {
    Markdown snapshot = document;
    String selectedPath = path;
    LocalDate selectedDay = day;
    SafVaultStorage selectedStorage = storage;
    if (checklistContext != null && checklistPath.equals(path)) {
      // Keep the table height stable during refresh; recompute facts from the new document now.
      loaded.accept(DailyChecklist.rows(snapshot, checklistContext, contentRules));
      if (checklistText.equals(snapshot.text)) return;
    }
    io.execute(
        () -> {
          try {
            var context = DailyChecklist.load(selectedStorage, selectedPath, selectedDay);
            runOnUiThread(
                () -> {
                  if (!isDestroyed()
                      && screen.equals("day")
                      && path.equals(selectedPath)
                      && document == snapshot) {
                    checklistContext = context;
                    checklistPath = selectedPath;
                    checklistText = snapshot.text;
                    loaded.accept(DailyChecklist.rows(snapshot, context, contentRules));
                  }
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (!isDestroyed() && screen.equals("day") && path.equals(selectedPath))
                    failed.accept("Не удалось загрузить чеклист");
                });
          }
        });
  }

  private void startWeather() {
    if (busy || document == null) return;
    cancelWeather();
    cancelNavigation();
    try {
      editor = EditorState.weather(path, document);
      recovery.save(editor, vault);
      showEditor();
    } catch (IllegalArgumentException error) {
      ui.error(error.getMessage());
    }
  }

  private void cancelWeather() {
    weatherVersion++;
    weatherLoading = false;
    if (weatherJob != null) weatherJob.cancel(true);
  }

  private void stopWeatherInEditor() {
    cancelWeather();
    ui.loading(false);
  }

  private void chooseWeatherLocation() {
    if (busy) return;
    stopWeatherInEditor();
    WeatherLocationPicker.show(
        ui,
        prefs.getString("weatherCity", "vidnoe"),
        city -> {
          prefs.edit().putString("weatherCity", city).apply();
          showEditor();
        });
  }

  private void loadWeather() {
    if (busy || editor == null || weatherLoading) return;
    EditorState form = editor;
    String city = prefs.getString("weatherCity", "vidnoe");
    LocalDate date = LocalDate.parse(DiaryLayout.fileName(form.destinationPath).substring(0, 10));
    long token = ++weatherVersion;
    weatherLoading = true;
    ui.error(null);
    ui.loading(true);
    weatherJob =
        weatherIo.submit(
            () -> {
              try {
                var summary = WeatherSummary.fetch(city, date, ZoneId.systemDefault());
                runOnUiThread(() -> finishWeather(token, form, summary, null));
              } catch (Exception error) {
                runOnUiThread(
                    () ->
                        finishWeather(
                            token,
                            form,
                            null,
                            error.getMessage() == null
                                ? "Не удалось получить погоду"
                                : error.getMessage()));
              }
            });
  }

  private void finishWeather(long token, EditorState form, WeatherSummary summary, String error) {
    if (isDestroyed() || token != weatherVersion || editor != form) return;
    if (busy) {
      handler.postDelayed(() -> finishWeather(token, form, summary, error), 100);
      return;
    }
    weatherLoading = false;
    ui.loading(false);
    if (summary != null) {
      String before = editor.editableText();
      editor.addWeather(summary);
      if (!editor.editableText().equals(before)) {
        recovery.save(editor, vault);
        showEditor();
      }
    }
    if (error != null) ui.error(error);
  }

  private void changePassword(int operation) {
    if (busy) return;
    String selectedVault = vault;
    PasswordDialogs.settings(
        ui,
        operation,
        (first, second) ->
            work(
                () -> {
                  if (operation == 0) passwords.create(selectedVault, first, second);
                  else if (operation == 1 || operation == 2)
                    passwords.change(selectedVault, first, second, operation == 1);
                  else passwords.delete(selectedVault, first);
                  return true;
                },
                ok -> {
                  handler.removeCallbacks(expirePassword);
                  showSettings();
                }));
  }

  private void toggleSecret() {
    if (busy || editor == null) return;
    if (!passwords.exists(vault)) {
      ui.error("Сначала создайте пароль в настройках");
      return;
    }
    EditorState owner = editor;
    int part = owner.parts == null ? -1 : owner.parts.selected;
    Runnable apply =
        () -> {
          char[] password = passwords.session.copy();
          if (password == null) {
            ui.error("Введите PIN снова");
            return;
          }
          work(
              () -> {
                try {
                  if (owner.parts != null) owner.parts.toggleSecret(part, password);
                  else owner.toggleSecret(password, template);
                  recovery.save(owner, vault);
                  return true;
                } finally {
                  Arrays.fill(password, '\0');
                }
              },
              ok -> {
                if (editor == owner) showEditor();
              });
        };
    char[] cached = passwords.session.copy();
    if (cached != null) {
      Arrays.fill(cached, '\0');
      apply.run();
      return;
    }
    String selectedVault = vault;
    PasswordDialogs.pin(
        ui,
        "PIN шифрования",
        pin ->
            work(
                () -> {
                  passwords.unlock(selectedVault, pin);
                  if (destroying || isDestroyed()) passwords.close();
                  return true;
                },
                ok -> {
                  handler.removeCallbacks(expirePassword);
                  handler.postDelayed(expirePassword, passwords.session.remaining());
                  if (editor == owner && vault.equals(selectedVault)) apply.run();
                }));
  }

  private HiddenSections hiddenSections(Markdown source) {
    return new HiddenSections(
        source == null ? new Markdown("") : source, template, sectionVisibility.load(vault));
  }

  private HiddenSections hiddenFor(EditorState form) {
    Markdown source =
        document != null && form.destinationSnapshot.equals(document.text)
            ? document
            : new Markdown(form.destinationSnapshot);
    return hiddenSections(source);
  }

  private void showHiddenSections() {
    if (busy) return;
    screen = "section-visibility";
    var source = document == null ? new Markdown("") : document;
    String selectedVault = vault;
    SectionVisibilityScreen.show(
        ui,
        hiddenSections(source),
        new Routing.Catalog(source, template),
        this::showSettings,
        visibility -> sectionVisibility.save(selectedVault, visibility.explicit()));
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
        passwords.exists(vault),
        (int)
            hiddenSections(document).entries().stream()
                .filter(HiddenSections.Entry::hidden)
                .count(),
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

          public void password(int operation) {
            changePassword(operation);
          }

          public void sections() {
            showHiddenSections();
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
