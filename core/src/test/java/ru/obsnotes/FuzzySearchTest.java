package ru.obsnotes;

import static org.junit.Assert.*;

import org.junit.Test;

public class FuzzySearchTest {
  @Test
  public void matchesLettersWithGapsInOrder() {
    assertTrue(FuzzySearch.matches("Почитал книги", "пчткн"));
    assertFalse(FuzzySearch.matches("Почитал книги", "кнпч"));
    assertFalse(FuzzySearch.matches("Мама", "ммм"));
  }

  @Test
  public void handlesCaseUnicodeAndWhitespace() {
    assertTrue(FuzzySearch.matches("Ещё прогулка", "Ё ПРГ"));
    assertTrue(FuzzySearch.matches("𐐀a𐐀", "𐐨𐐨"));
    assertTrue(FuzzySearch.matches("что угодно", "  "));
  }

  @Test
  public void punctuationAndAliasRemainSearchable() {
    assertTrue(FuzzySearch.matches("Погода:: снег", "пг::с"));
    assertTrue(FuzzySearch.matches("Здоровье\nBodyHealth", "bdhl"));
    assertFalse(FuzzySearch.matches("Погода:: снег", "пг/"));
  }

  @Test
  public void fuzzyLettersCannotCrossIndependentSearchFieldsOrRepeatedTitles() {
    for (String separator : new String[] {"\n", "\r\n", "\r", "\u2028"}) {
      assertFalse(FuzzySearch.matches("ab" + separator + "ab", "ba "));
      assertEquals(Integer.MAX_VALUE, FuzzySearch.rank("ab" + separator + "ab", "ba "));
      assertFalse(FuzzySearch.matches("Арбуз" + separator + "Арбуз", "за "));
      assertTrue(FuzzySearch.matches("No match" + separator + "BodyHealth", "bdhl "));
    }
    assertTrue(FuzzySearch.matches("abc def", "a d f "));
    assertEquals(0, FuzzySearch.rank("Label\nExact", " Exact "));
    assertEquals(1, FuzzySearch.rank("Label\nLonger Exact value", "Exact "));
    assertEquals(2, FuzzySearch.rank("Label\nE-x-a-c-t", "Exact "));
  }
}
