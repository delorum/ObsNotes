package ru.obsnotes;

import java.io.*;
import java.util.*;

public interface VaultStorage {
  String read(String path) throws IOException;

  void write(String path, String text) throws IOException;

  List<String> dailyNotes() throws IOException;
}
