package ru.obsnotes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** App-private write-ahead log; completed transactions retain ten local recovery copies. */
public final class FileJournal implements VaultRepository.Journal {
  private final Path directory, pending;

  public FileJournal(Path directory) throws IOException {
    this.directory = directory;
    Files.createDirectories(directory);
    pending = directory.resolve("pending.bin");
  }

  private static void string(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String string(DataInputStream in) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > 32 * 1024 * 1024) throw new IOException("Повреждён журнал");
    byte[] bytes = new byte[n];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public void prepare(List<VaultRepository.Change> changes) throws IOException {
    if (Files.exists(pending)) throw new IOException("Есть незавершённое сохранение");
    Path temp = directory.resolve("preparing.bin");
    try (FileOutputStream file = new FileOutputStream(temp.toFile());
        DataOutputStream out = new DataOutputStream(file)) {
      out.writeInt(changes.size());
      for (var c : changes) {
        string(out, c.path());
        string(out, c.before());
        string(out, c.after());
      }
      out.flush();
      file.getFD().sync();
    }
    Files.move(temp, pending, StandardCopyOption.ATOMIC_MOVE);
  }

  public List<VaultRepository.Change> pending() throws IOException {
    if (!Files.exists(pending)) return List.of();
    try (DataInputStream in = new DataInputStream(Files.newInputStream(pending))) {
      int count = in.readInt();
      if (count < 1 || count > 10) throw new IOException("Повреждён журнал");
      List<VaultRepository.Change> changes = new ArrayList<>();
      for (int i = 0; i < count; i++)
        changes.add(new VaultRepository.Change(string(in), string(in), string(in)));
      return List.copyOf(changes);
    }
  }

  public void complete() throws IOException {
    if (!Files.exists(pending)) return;
    Files.move(
        pending,
        directory.resolve(
            "backup-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".bin"));
    try (var files = Files.list(directory)) {
      List<Path> backups =
          files
              .filter(p -> p.getFileName().toString().startsWith("backup-"))
              .sorted(Comparator.reverseOrder())
              .collect(java.util.stream.Collectors.toList());
      for (int i = 10; i < backups.size(); i++) Files.delete(backups.get(i));
    }
  }
}
