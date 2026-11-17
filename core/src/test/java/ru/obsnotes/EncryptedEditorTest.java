package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class EncryptedEditorTest {
  static final String CLEAR = "Личная заметка\nВторая строка";
  static final LocalDate DATE = LocalDate.of(2026, 11, 17);
  static final String PATH = DiaryLayout.path(DATE),
      SECRET = "`secret " + InlineCrypto.encrypt(CLEAR, password()) + "`";
  static final Markdown TEMPLATE = new Markdown("## Заметки\n\n## Работа\n\n");

  static Markdown doc(String body) {
    return new Markdown("## Заметки\n\n" + body + "\n\n## Работа\n\nTail\n");
  }

  static EditorState edit(Markdown m) {
    var s = EditorState.editing(PATH, m, TEMPLATE, m.blocks.get(0));
    s.enableParts(TEMPLATE);
    return s;
  }

  static List<VaultRepository.Change> plan(EditorState s, Markdown m) {
    return VaultRepository.plan(s, m, m, TEMPLATE, DATE, DATE.atTime(12, 0));
  }

  static char[] password() {
    return InlineCryptoTest.PASSWORD.toCharArray();
  }

  @Test
  public void closedSecretUsesPlaceholderInEditorAndOverviewButKeepsOriginalBytes() {
    var m = doc(SECRET);
    var s = edit(m);
    assertEquals(SecretText.PLACEHOLDER, s.editableText());
    assertEquals(SecretText.PLACEHOLDER, CrossReferences.clean(SECRET));
    assertTrue(plan(s, m).isEmpty());
    assertTrue(s.parts.parts.get(0).form().locked());
  }

  @Test
  public void readingAndClosingAgainWithoutEditsIsAnExactNoOp() {
    var m = new Markdown(doc(SECRET).text.replace("\n", "\r\n"));
    var s = edit(m);
    s.parts.toggleSecret(0, password());
    assertEquals(CLEAR, s.editableText());
    assertEquals(1, s.parts.parts.size());
    assertFalse(plan(s, m).isEmpty());
    assertTrue(plan(s, m).get(0).after().contains("Личная заметка"));
    s.parts.toggleSecret(0, password());
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void finalUnlockedPositionSavesPlaintextOnlyOnPlanningAndRetainsMetadata() {
    String root = Events.root("[[#^parent|Parent]]\n" + SECRET + " ^own");
    var m = doc(root);
    var s = edit(m);
    s.parts.toggleSecret(0, password());
    var result = plan(s, m).get(0).after();
    assertTrue(result.contains(CLEAR));
    assertTrue(result.contains("[[#^parent|Parent]]"));
    assertTrue(result.contains("^own"));
    assertTrue(result.contains("outLinkInfo"));
    assertEquals(root, m.blocks.get(0).text());
  }

  @Test
  public void editsInsideOpenedMultilineSecretRemainOnePartAndReencryptToNewPayload() {
    var m = doc(SECRET);
    var s = edit(m);
    s.parts.toggleSecret(0, password());
    s.edit(CLEAR.replace("Вторая", "Другая"));
    assertEquals(1, s.parts.parts.size());
    s.parts.toggleSecret(0, password());
    assertNotEquals(SECRET, s.parts.parts.get(0).form().text);
    assertFalse(plan(s, m).isEmpty());
    var secret = SecretText.scan(plan(s, m).get(0).after()).get(0);
    assertEquals(
        CLEAR.replace("Вторая", "Другая"), InlineCrypto.decrypt(secret.payload(), password()));
  }

  @Test
  public void openingTypingAndRevertingThenClosingDoesNotRewriteCiphertext() {
    var m = doc(SECRET);
    var s = edit(m);
    s.parts.toggleSecret(0, password());
    s.edit("Changed");
    s.edit(CLEAR);
    s.parts.toggleSecret(0, password());
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void cursorSelectsOnlyOnePartAndKeepsOtherAssignmentsAndParents() {
    var m = doc("First");
    var s = edit(m);
    s.edit("First\n\nSecond\n\nThird");
    s.parts.addInternal(2, 0, null);
    s.parts.selectAt(8);
    s.parts.toggleSecret(s.parts.selected, password());
    assertFalse(s.parts.parts.get(0).form().locked());
    assertTrue(s.parts.parts.get(1).form().locked());
    assertFalse(s.parts.parts.get(2).form().locked());
    assertEquals(1, s.parts.references(2).size());
    s.parts.toggleSecret(1, password());
    assertEquals("First\n\nSecond\n\nThird", s.editableText());
  }

  @Test
  public void typingAroundAnOpaqueSecretPreservesItsPayloadAndCanMoveTheBlock() {
    var m = doc("Before " + SECRET + " after");
    var s = edit(m);
    s.edit("Prefix Before " + SecretText.PLACEHOLDER + " after!");
    var f = s.parts.parts.get(0).form();
    f.chooseTarget(new Markdown.Target("Работа", "## Работа", -1, "", Markdown.Kind.SECTION));
    String result = plan(s, m).get(0).after();
    assertTrue(result.contains("Prefix Before " + SECRET + " after!"));
  }

  @Test
  public void partialPlaceholderEditsAreRejectedWithoutLosingTheOriginalPayload() {
    var m = doc(SECRET);
    var s = edit(m);
    s.edit(SecretText.PLACEHOLDER.substring(1));
    assertFalse(s.parts.error.isEmpty());
    assertTrue(s.parts.parts.get(0).form().text.contains(SecretText.scan(SECRET).get(0).payload()));
    s.edit(SecretText.PLACEHOLDER);
    assertEquals("", s.parts.error);
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void deletingAWholePlaceholderIsAnExplicitBlockDeletion() {
    var m = doc(SECRET);
    var s = edit(m);
    s.edit("");
    assertFalse(plan(s, m).get(0).after().contains("`secret"));
  }

  @Test
  public void wrongPasswordAndPartlyInvalidMultipleSecretsAreAtomic() {
    var m = doc("`secret broken` " + SECRET);
    var s = edit(m);
    String before = s.parts.parts.get(0).form().text;
    assertThrows(IllegalArgumentException.class, () -> s.parts.toggleSecret(0, password()));
    assertEquals(before, s.parts.parts.get(0).form().text);
    assertTrue(plan(s, m).isEmpty());
  }

  @Test
  public void codeFencesAndDoubleBackticksStayLiteral() {
    assertFalse(SecretText.has("```md\n" + SECRET + "\n```"));
    assertFalse(SecretText.has("`" + SECRET + "`"));
    assertEquals("`" + SECRET + "`", SecretText.display("`" + SECRET + "`"));
  }

  @Test
  public void multipleOpaqueRegionsAreIndependentlyPreservedWhenOneIsDeleted() {
    String a = "`secret aaa`", b = "`secret bbb`";
    String raw = a + " and " + b;
    assertEquals(" and " + b, SecretText.edit(raw, " and " + SecretText.PLACEHOLDER));
    assertEquals(a + " and ", SecretText.edit(raw, SecretText.PLACEHOLDER + " and "));
    assertEquals("prefix " + raw, SecretText.edit(raw, "prefix " + SecretText.display(raw)));
  }

  @Test
  public void conflictCopyKeepsClosedCiphertextInsteadOfSavingThePlaceholder() {
    var s = edit(doc(SECRET));
    assertEquals(SECRET, s.conflictCopyText());
  }

  @Test
  public void decryptedMarkdownRemainsContentRatherThanBecomingEditorMetadata() {
    for (String clear :
        List.of(
            "Value:: text", "Text ^private", "[[#^payload|Body]]\nText", "A\n> [!info]\n> quote")) {
      String cipher = "`secret " + InlineCrypto.encrypt(clear, password()) + "`";
      var m = doc(cipher);
      var state = edit(m);
      state.parts.toggleSecret(0, password());
      assertEquals(clear, state.editableText());
      assertFalse(state.parts.parts.get(0).form().editFields);
      state.parts.toggleSecret(0, password());
      assertTrue(plan(state, m).isEmpty());
    }
  }

  @Test
  public void internalOpenedEnvelopeNeverReachesMarkdownEvenAfterEditingAndLinking() {
    var m = doc(SECRET);
    var state = edit(m);
    state.parts.toggleSecret(0, password());
    state.edit(CLEAR + " edited");
    var changes = plan(state, m);
    assertFalse(changes.get(0).after().contains("obsnotes-open"));
    assertTrue(changes.get(0).after().contains(" edited"));
  }

  @Test
  public void decryptedBlankLinesSplitPartsAndOnlyThePartAtTheCaretIsEncrypted() {
    String secret = "`secret " + InlineCryptoTest.VECTOR + "`";
    var m = doc(secret);
    var state = edit(m);
    state.parts.toggleSecret(0, password());
    assertEquals(2, state.parts.parts.size());
    assertEquals("Личная заметка\nВторая строка", state.parts.parts.get(0).form().editableText());
    assertEquals("Третий абзац 🦉", state.parts.parts.get(1).form().editableText());
    state.parts.selectAt(state.parts.parts.get(1).start());
    state.parts.toggleSecret(state.parts.selected, password());
    assertEquals(
        "Личная заметка\nВторая строка\n\n" + SecretText.PLACEHOLDER, state.editableText());
    String saved = plan(state, m).get(0).after();
    assertEquals(1, SecretText.scan(saved).size());
    assertEquals(
        "Третий абзац 🦉",
        InlineCrypto.decrypt(SecretText.scan(saved).get(0).payload(), password()));
  }

  @Test
  public void literalMalformedInternalLookingCodeCannotCrashAPreview() {
    assertEquals("`obsnotes-open A`", CrossReferences.clean("`obsnotes-open A`"));
  }

  @Test
  public void readingAndClosingEmptyOrBoundaryWhitespaceSecretIsLossless() {
    for (String clear : List.of("", "  ", "\nText\n", "Text\n\n")) {
      String secret = "`secret " + InlineCrypto.encrypt(clear, password()) + "`";
      var m = doc(secret);
      var state = edit(m);
      state.parts.toggleSecret(0, password());
      assertEquals(clear, state.editableText());
      state.parts.toggleSecret(0, password());
      assertTrue(plan(state, m).isEmpty());
    }
  }
}
