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
}
