package ru.obsnotes;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class WeatherTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
  private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

  private String wrap(LocalDate date, String rows) {
    long ts = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
    return "<div class='rf' data-ts='"
        + ts
        + "'><div class='ng' data-ts='"
        + ts
        + "'><div class='wg'>"
        + rows
        + "</div></div></div>";
  }

  private String row(String period, String description, String temperature) {
    return "<div class='ug'><span class='xg'>"
        + period
        + "</span><span class='yg'>"
        + description
        + "</span><span class='zg'>"
        + temperature
        + "</span></div>";
  }

  @Test
  public void identicalPeriodsCollapseAndSignedZeroIsNormalized() {
    String html =
        wrap(
            DAY,
            row("Вечер", "вечером ясно", "-0°")
                + row("Ночь", "ночью ясно", "+0°")
                + row("День", "днем ясно", "0°")
                + row("Утро", "утром ясно", "0°"));
    assertEquals(new WeatherSummary("ясно", "0"), WeatherSummary.parse(html, DAY, ZONE));
  }

  @Test
  public void differingPeriodsAreAlwaysChronologicalAndHtmlEntitiesAreDecoded() {
    String html =
        wrap(
            DAY, row("Вечер", "вечером снег", "-2°") + row("Утро", "утром ясно &amp; тихо", "+1°"));
    assertEquals(
        new WeatherSummary("утром ясно & тихо, вечером снег", "утром +1, вечером -2"),
        WeatherSummary.parse(html, DAY, ZONE));
  }

  @Test
  public void exactYearAndInnerDateAreRequiredAndMissingDataNeverFallbacks() {
    String previous = wrap(DAY.minusYears(1), row("День", "ясно", "+9°"));
    assertTrue(WeatherSummary.parse(previous, DAY, ZONE).empty());
    assertTrue(WeatherSummary.parse("<div class='rf' data-ts='bad'></div>", DAY, ZONE).empty());
    String wrongInner =
        wrap(DAY, row("День", "ясно", "+5°"))
            .replaceFirst("class='ng' data-ts='[0-9]+'", "class='ng' data-ts='0'");
    assertTrue(WeatherSummary.parse(wrongInner, DAY, ZONE).empty());
  }

  @Test
  public void incompleteAndUnknownPeriodRowsAreSkipped() {
    assertTrue(
        WeatherSummary.parse(
                wrap(
                    DAY,
                    row("Сейчас", "ясно", "+5°")
                        + "<div class=ug><span class=xg>День</span></div>"),
                DAY,
                ZONE)
            .empty());
  }

  @Test
  public void cityUriIsRestrictedToNuipogodaAndIncludesDateYearAndEncodedMonth() {
    var uri = WeatherSummary.uri("vidnoe", DAY);
    assertEquals("vidnoe.nuipogoda.ru", uri.getHost());
    assertEquals("/16-ноября", uri.getPath());
    assertEquals("2026", uri.getFragment());
    assertTrue(uri.toASCIIString().contains("%D0"));
    for (String bad : List.of("https://other.ru", "bad/name", "../bad", "", "a.b", "a@b"))
      assertThrows(IllegalArgumentException.class, () -> WeatherSummary.uri(bad, DAY));
  }

  @Test
  public void appendingKeepsManualTextAvoidsDuplicatesAndReplacesPrompts() {
    assertEquals("моё; ясно", InlineFields.append("моё", "ясно"));
    assertEquals("Ясно", InlineFields.append("Ясно", "ясно"));
    assertEquals("ясно", InlineFields.append("Заполни погоду!", "ясно"));
  }

  @Test
  public void weatherFormUpdatesBothKeysIndependentlyWithoutTouchingOtherFieldsOrCode() {
    String raw =
        "---\r\n"
            + "key: value\r\n"
            + "---\r\n"
            + "## Погода\r\n\r\n"
            + "Погода:: вручную ^weather-id\r\n\r\n"
            + "```dataviewjs\r\n"
            + "Температура:: код\r\n"
            + "```\r\n\r\n"
            + "ВоЧтоОдевалсяНаУлицу:: куртка\r\n\r\n"
            + "Температура:: +5\r\n";
    var doc = new Markdown(raw);
    var s = EditorState.weather("2026-11-16-пн.md", doc);
    s.initializeRoutes(doc);
    assertEquals("вручную", s.fieldValue(0));
    s.addWeather(new WeatherSummary("ясно", "+1"));
    s.addWeather(new WeatherSummary("ясно", "+1"));
    String result = VaultRepository.plan(s, doc, doc, doc, DAY, DAY.atTime(12, 0)).get(0).after();
    assertEquals(
        raw.replace("Погода:: вручную ^weather-id", "Погода:: вручную; ясно ^weather-id")
            .replace("Температура:: +5", "Температура:: +5; +1"),
        result);
  }

  @Test
  public void openingWeatherFormAndExitingWithoutChangesIsAnExactNoOp() {
    var doc = new Markdown("## Заметки\n\n## Погода\n\nПогода::\tясно\n");
    var s = EditorState.weather("2026-11-16-пн.md", doc);
    assertEquals(doc.text, s.savedWeather(doc, doc).text);
  }

  @Test
  public void missingWeatherKeysAreRestoredOnlyWhenChangedInAnExistingNote() {
    var doc = new Markdown("## Заметки\n\nтекст\n");
    var template = new Markdown("## Заметки\n\n## Погода\n");
    var s = EditorState.weather("2026-11-16-пн.md", doc);
    s.editField(0, "ясно");
    var out = s.savedWeather(doc, template);
    assertTrue(out.text.contains("Погода:: ясно"));
    assertFalse(out.text.contains("Температура::"));
    assertTrue(out.text.contains("текст"));
  }

  @Test
  public void repeatedKeysAndMultilineValuesAreRejectedWithoutChangingDocument() {
    var doc = new Markdown("## Погода\nПогода:: a\nПогода:: b\n");
    assertThrows(IllegalArgumentException.class, () -> EditorState.weather("day.md", doc));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InlineFields.write(
                new Markdown("## Погода\n"), Map.of("Погода", "a\nb"), "## Погода", doc));
  }
}
