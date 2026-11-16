package ru.obsnotes;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

public interface VaultStorage {
  String read(String path) throws IOException;

  void write(String path, String text) throws IOException;

  List<String> dailyNotes() throws IOException;

  default long modifiedMillis(String path) throws IOException {
    return 0;
  }

  @FunctionalInterface
  interface NoteVisitor {
    void visit(String path) throws IOException;
  }

  default void visitDailyNotes(BooleanSupplier cancelled, NoteVisitor visitor) throws IOException {
    if (cancelled.getAsBoolean()) return;
    for (String path : dailyNotes()) {
      if (cancelled.getAsBoolean()) return;
      visitor.visit(path);
    }
  }
}
