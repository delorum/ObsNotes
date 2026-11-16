package ru.obsnotes;

import java.util.*;

/** Shared font/chip palette. Generated pastel colors never wrap back to an earlier line. */
public final class LineColors {
  private LineColors() {}

  private static final List<Integer> COLORS =
      new ArrayList<>(
          List.of(0xffa7cd65, 0xffe8be63, 0xff7cb4ed, 0xffef9ba5, 0xff6bcfc3, 0xffe6a66c));
  private static final Set<Integer> USED = new HashSet<>(COLORS);
  private static int generated;

  public static synchronized int argb(int index) {
    if (index < 0 || index > 2_000_000) throw new IllegalArgumentException("Неверный индекс цвета");
    while (COLORS.size() <= index) {
      int code = (generated++ * 1036889) & 0x1fffff;
      int color =
          0xff000000
              | (128 + (code & 127)) << 16
              | (128 + ((code >> 7) & 127)) << 8
              | 128 + ((code >> 14) & 127);
      if (USED.add(color)) COLORS.add(color);
    }
    return COLORS.get(index);
  }
}
