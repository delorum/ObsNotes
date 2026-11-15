package ru.obsnotes;

import static org.junit.Assert.*;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class DiaryIndexTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 15);

  private static final class Storage implements VaultStorage {
    List<String> reads = new ArrayList<>();
    boolean missing = false;

    public String read(String path) throws IOException {
      reads.add(path);
      if (path.equals(TemplateCatalog.PATH)) return "## Заметки\n\n## Лена\n";
      if (path.equals(DiaryLayout.path(DATE)) && !missing) return "## Заметки\n\nexisting day\n";
      throw new FileNotFoundException(path);
    }

    public void write(String path, String text) {
      throw new AssertionError("Opening must not write");
    }

    public List<String> dailyNotes() {
      throw new AssertionError("Opening must not enumerate the diary archive");
    }
  }

  @Test
  public void openingExistingDayOnlyReadsTemplateAndRequestedFile() throws IOException {
    Storage storage = new Storage();
    var opened = new DiaryIndex().open(storage, DATE);
    assertEquals(List.of(TemplateCatalog.PATH, DiaryLayout.path(DATE)), storage.reads);
    assertEquals(DiaryLayout.path(DATE), opened.path());
    assertNotNull(opened.document());
    assertEquals(2, opened.template().sections.size());
  }

  @Test
  public void openingMissingDayDoesNotScanOrCreateAnything() throws IOException {
    Storage storage = new Storage();
    storage.missing = true;
    assertNull(new DiaryIndex().open(storage, DATE).document());
    assertEquals(2, storage.reads.size());
  }

  @Test
  public void otherReadFailuresAreNotReportedAsMissingDays() {
    VaultStorage storage =
        new VaultStorage() {
          public String read(String path) throws IOException {
            if (path.equals(TemplateCatalog.PATH)) return "## Заметки\n";
            throw new IOException("permission revoked");
          }

          public void write(String p, String s) {
            throw new AssertionError();
          }

          public List<String> dailyNotes() {
            throw new AssertionError();
          }
        };
    IOException error = assertThrows(IOException.class, () -> new DiaryIndex().open(storage, DATE));
    assertEquals("permission revoked", error.getMessage());
  }

  @Test
  public void selectingDestinationReadsOnlyTheCalculatedDatePath() throws IOException {
    Storage storage = new Storage();
    var selected = new DiaryIndex().day(storage, DATE);
    assertEquals(List.of(DiaryLayout.path(DATE)), storage.reads);
    assertEquals(DiaryLayout.path(DATE), selected.path());
  }

  @Test
  public void missingDestinationFailsWithoutCreatingFileOrScanningArchive() {
    Storage storage = new Storage();
    storage.missing = true;
    assertThrows(FileNotFoundException.class, () -> new DiaryIndex().day(storage, DATE));
    assertEquals(List.of(DiaryLayout.path(DATE)), storage.reads);
  }
}
