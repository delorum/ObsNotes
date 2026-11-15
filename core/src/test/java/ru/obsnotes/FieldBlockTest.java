package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class FieldBlockTest {
  private static EditorState state(String body) {
    var m = new Markdown("## Заметки\n\n" + body + "\n");
    var b = m.blocks.get(0);
    EditorState s = new EditorState();
    s.fresh = false;
    s.sourcePath = s.destinationPath = "day.md";
    s.sourceSnapshot = s.destinationSnapshot = m.text;
    s.original = s.text = b.text();
    s.start = b.start();
    s.end = b.end();
    s.initializeRoutes(m);
    return s;
  }

  @Test
  public void existingFieldsExposeOnlyValuesAndPreserveKeysIncludingEmptyValues() {
    var s = state("Погода:: снег\nShortDescr:: события");
    assertEquals(2, s.fieldCount());
    assertEquals("Погода", s.fieldName(0));
    assertEquals("снег\nсобытия", s.editableText());
    s.editField(0, "");
    s.editField(1, "новое");
    assertEquals("Погода:: \nShortDescr:: новое", s.savedText());
    var m = new Markdown(s.sourceSnapshot);
    assertTrue(
        VaultRepository.plan(s, m, m, m, LocalDate.now(), LocalDateTime.now())
            .get(0)
            .after()
            .contains("Погода:: \nShortDescr:: новое"));
  }

  @Test
  public void editingValueKeepsHiddenTagsAndRootAnchor() {
    var s = state(Events.root("Погода:: снег #crossOneLine/Утро"));
    String id = Events.id(s.text);
    assertEquals("снег", s.fieldValue(0));
    s.editField(0, "ветер");
    assertEquals("ветер", s.editableText());
    assertTrue(s.savedText().contains("Погода:: ветер #crossOneLine/Утро ^" + id));
    assertTrue(s.savedText().contains("outLinkInfo"));
  }

  @Test
  public void pastedLineBreaksStayInValueAndCannotCreateAnotherField() {
    var s = state("Погода:: ясно");
    s.editField(0, "новое\r\nКлюч:: значение");
    assertEquals(1, s.fieldCount());
    assertEquals("новое; Ключ:: значение", s.fieldValue(0));
    assertTrue(s.savedText().startsWith("Погода:: новое; Ключ:: значение"));
  }

  @Test
  public void ordinaryNotesAndNewFieldLikeTextRemainOrdinaryEditor() {
    assertEquals(0, state("Погода:: снег\nобычный текст").fieldCount());
    var s = new EditorState();
    s.edit("Поле:: значение");
    assertEquals("Поле:: значение", s.editableText());
    assertEquals(0, s.fieldCount());
  }

  @Test
  public void prefixWhitespaceSurvivesValueReplacement() {
    var f = FieldBlock.parse("  Key::\tvalue");
    assertEquals("  Key::\tother", f.edit(0, "other"));
  }
}
