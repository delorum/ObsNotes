package ru.obsnotes;

import static org.junit.Assert.*;

import org.junit.Test;

public class LineMarksTest {
  @Test
  public void wholeBlockCrossPeopleAndPersonNeverHighlightText() {
    var t = TaggedText.parse("видел #person/Лена #crossPeople/Утро");
    assertTrue(LineMarks.of(t.clean, t.tags).isEmpty());
  }

  @Test
  public void severalTagsOfOneLineShareAColorAndOtherLinesDiffer() {
    var t =
        TaggedText.parse(
            "раз #crossOneLine/Утро #personOneLine/Лена\n"
                + "два #crossPeople/Покупки\n"
                + "три #crossOneLine/День");
    var marks = LineMarks.of(t.clean, t.tags);
    assertEquals(2, marks.size());
    assertEquals(1, marks.get(0).line());
    assertEquals(3, marks.get(1).line());
    assertNotEquals(marks.get(0).colorIndex(), marks.get(1).colorIndex());
  }

  @Test
  public void insertingAnOrdinaryLineKeepsTagColors() {
    var before = TaggedText.parse("раз #crossOneLine/Утро\nдва #crossOneLine/День");
    var after = before.edit("новая\nраз\nдва");
    var a = LineMarks.of(before.clean, before.tags);
    var b = LineMarks.of(after.clean, after.tags);
    assertEquals(a.get(0).colorIndex(), b.get(0).colorIndex());
    assertEquals(a.get(1).colorIndex(), b.get(1).colorIndex());
    assertEquals(2, b.get(0).line());
    assertEquals(3, b.get(1).line());
  }

  @Test
  public void highlightRangesCoverTheCleanTextOfTheirLines() {
    var t = TaggedText.parse("раз\nдва #crossOneLine/Утро\nтри");
    var mark = LineMarks.of(t.clean, t.tags).get(0);
    assertEquals("два", t.clean.substring(mark.start(), mark.end()));
  }
}
