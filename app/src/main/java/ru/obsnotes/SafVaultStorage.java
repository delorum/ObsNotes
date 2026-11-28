package ru.obsnotes;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** All calls run on background executors. Never creates files or directories. */
public final class SafVaultStorage implements VaultStorage {
  private final Context context;
  private final Uri tree;

  public SafVaultStorage(Context context, Uri tree) {
    this.context = context.getApplicationContext();
    this.tree = tree;
  }

  public void validate() throws IOException {
    DocumentFile root = DocumentFile.fromTreeUri(context, tree);
    if (root == null || !root.isDirectory() || !root.canRead() || !root.canWrite())
      throw new IOException("Выберите папку хранилища и разрешите чтение и запись");
    resolve("Дневник/Хроника");
    resolve(TemplateCatalog.PATH);
  }

  private record Entry(String name, Uri uri, boolean directory) {}

  private List<Entry> children(Uri directory) throws IOException {
    Uri query =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getDocumentId(directory));
    String[] columns = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    List<Entry> list = new ArrayList<>();
    try (Cursor cursor = context.getContentResolver().query(query, columns, null, null, null)) {
      if (cursor == null) throw new IOException("Папка недоступна");
      while (cursor.moveToNext())
        list.add(
            new Entry(
                cursor.getString(1),
                DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
    }
    return list;
  }

  private Uri resolve(String path) throws IOException {
    if (path.startsWith("/") || Arrays.asList(path.split("/")).contains(".."))
      throw new IOException("Неверный путь");
    String root = DocumentsContract.getTreeDocumentId(tree);
    if ("com.android.externalstorage.documents".equals(tree.getAuthority()))
      return DocumentsContract.buildDocumentUriUsingTree(
          tree, root + (root.endsWith(":") ? "" : "/") + path);
    return resolveByComponents(path);
  }

  /** Fallback also distinguishes a missing document from an expired grant on ExternalStorage. */
  private Uri resolveByComponents(String path) throws IOException {
    Uri current =
        DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
    for (String part : path.split("/")) {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Проверка отменена");
      Entry found = null;
      for (Entry e : children(current))
        if (e.name.equals(part)) {
          if (found != null) throw new IOException("Неоднозначный путь: " + path);
          found = e;
        }
      if (found == null) throw new FileNotFoundException(path);
      current = found.uri;
    }
    return current;
  }

  @Override
  public boolean exists(String path) throws IOException {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                resolve(path),
                new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null,
                null,
                null)) {
      if (cursor == null) {
        var failure = new IOException("Проверка файла недоступна");
        if (missingPath(path, failure) != null) return false;
        throw failure;
      }
      return cursor.moveToFirst();
    } catch (FileNotFoundException missing) {
      return false;
    } catch (SecurityException | IllegalArgumentException failure) {
      if (missingPath(path, failure) != null) return false;
      throw failure;
    }
  }

  /** A provider may return null or wrap a missing document as a runtime exception. */
  private FileNotFoundException missingPath(String path, Throwable failure) throws IOException {
    if (!DayNavigation.missing(failure)) {
      if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
      int slash = path.lastIndexOf('/');
      if (slash > 0) {
        try {
          for (var entry : children(resolve(path.substring(0, slash))))
            if (entry.name().equals(path.substring(slash + 1))) return null;
          var missing = new FileNotFoundException(path);
          missing.initCause(failure);
          return missing;
        } catch (IOException | SecurityException | IllegalArgumentException unavailableParent) {
          if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Проверка отменена");
          // A missing parent or expired grant still requires checking the named components.
        }
      }
      try {
        resolveByComponents(path);
        return null;
      } catch (FileNotFoundException absent) {
        // Only the explicitly requested components are checked, never the diary archive.
      }
    }
    var missing = new FileNotFoundException(path);
    missing.initCause(failure);
    return missing;
  }

  public String read(String path) throws IOException {
    try (InputStream in = context.getContentResolver().openInputStream(resolve(path));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (in == null) throw new FileNotFoundException(path);
      byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("Поиск отменён");
        if (out.size() + n > 16 * 1024 * 1024) throw new IOException("Файл больше 16 МБ");
        out.write(buffer, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    } catch (SecurityException | IllegalArgumentException failure) {
      var missing = missingPath(path, failure);
      if (missing != null) throw missing;
      throw failure;
    }
  }

  public void write(String path, String text) throws IOException {
    // Opening an existing URI in rwt mode cannot create a missing daily note.
    try (OutputStream out = context.getContentResolver().openOutputStream(resolve(path), "rwt")) {
      if (out == null) throw new IOException("Запись недоступна: " + path);
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  public List<String> dailyNotes() throws IOException {
    List<String> list = new ArrayList<>();
    visitDailyNotes(() -> false, list::add);
    list.sort(Comparator.comparing(DiaryLayout::fileName).reversed());
    return List.copyOf(list);
  }

  public long modifiedMillis(String path) throws IOException {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                resolve(path),
                new String[] {DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null,
                null,
                null)) {
      return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : 0;
    }
  }

  @Override
  public void visitDailyNotes(BooleanSupplier cancelled, NoteVisitor visitor) throws IOException {
    if (cancelled.getAsBoolean()) return;
    walk(resolve("Дневник/Хроника"), "Дневник/Хроника", cancelled, visitor, 6, true);
  }

  @Override
  public void visitMarkdownNotes(BooleanSupplier cancelled, NoteVisitor visitor)
      throws IOException {
    var root =
        DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
    walk(root, "", cancelled, visitor, Integer.MAX_VALUE, false);
  }

  private static final java.util.regex.Pattern DAILY_NAME =
      java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}-[а-я]{2}\\.md");

  private void walk(
      Uri directory,
      String prefix,
      BooleanSupplier cancelled,
      NoteVisitor visitor,
      int levels,
      boolean dailyOnly)
      throws IOException {
    if (levels < 0 || cancelled.getAsBoolean()) return;
    for (Entry e : children(directory)) {
      if (cancelled.getAsBoolean()) return;
      if (e.name.startsWith(".")) continue;
      String path = prefix.isEmpty() ? e.name : prefix + "/" + e.name;
      if (e.directory) walk(e.uri, path, cancelled, visitor, levels - 1, dailyOnly);
      else if (dailyOnly
          ? DAILY_NAME.matcher(e.name).matches()
          : e.name.toLowerCase(Locale.ROOT).endsWith(".md")) visitor.visit(path);
    }
  }
}
