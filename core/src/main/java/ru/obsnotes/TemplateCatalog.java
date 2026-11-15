package ru.obsnotes;

/** Reads structure only; ObsNotes never creates daily notes or executes Templater. */
public final class TemplateCatalog {
  public static final String PATH = "Шаблоны/Шаблон дневниковой записи.md";

  private TemplateCatalog() {}

  public static Markdown parse(String source) {
    String structure = source.replaceAll("(?s)<%.*?%>", "");
    return new Markdown(structure);
  }
}
