package ru.obsnotes;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Matches the vault's nuipogoda date/period selection; never substitute another date. */
public record WeatherSummary(String weather, String temperature) {
  public record Location(String name, String code) {}

  public static final List<Location> LOCATIONS =
      List.of(
          new Location("Видное", "vidnoe"),
          new Location("Дача", "yusupovo-domodedovo-gorod-moskovskaya"),
          new Location("Кременки", "kremenki-kaluzhskaya"),
          new Location("Протвино", "protvino"),
          new Location("Москва", "msk"),
          new Location("Анталья", "antalya"));
  private static final String[] MONTHS = {
    "января",
    "февраля",
    "марта",
    "апреля",
    "мая",
    "июня",
    "июля",
    "августа",
    "сентября",
    "октября",
    "ноября",
    "декабря"
  };
  private static final String[] PERIODS = {"Ночь", "Утро", "День", "Вечер"},
      PREFIXES = {"ночью", "утром", "днем", "вечером"};

  public boolean empty() {
    return weather.isBlank() && temperature.isBlank();
  }

  public static URI uri(String city, LocalDate date) {
    if (!city.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
      throw new IllegalArgumentException("Нужен код города nuipogoda, например vidnoe");
    try {
      return new URI(
          "https",
          city + ".nuipogoda.ru",
          "/" + date.getDayOfMonth() + "-" + MONTHS[date.getMonthValue() - 1],
          null,
          Integer.toString(date.getYear()));
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static boolean date(Element element, LocalDate date, ZoneId zone) {
    try {
      return Instant.ofEpochMilli(Long.parseLong(element.attr("data-ts")))
          .atZone(zone)
          .toLocalDate()
          .equals(date);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public static WeatherSummary parse(String html, LocalDate requested, ZoneId zone) {
    var doc = Jsoup.parse(html);
    Element rf = null, ng = null;
    for (var candidate : doc.select(".rf[data-ts]"))
      if (date(candidate, requested, zone)) {
        rf = candidate;
        break;
      }
    if (rf == null) return new WeatherSummary("", "");
    for (var candidate : rf.select(".ng[data-ts]"))
      if (date(candidate, requested, zone)) {
        ng = candidate;
        break;
      }
    if (ng == null) return new WeatherSummary("", "");
    Map<String, String[]> values = new HashMap<>();
    for (var row : ng.select(".wg .ug")) {
      Element period = row.selectFirst(".xg"),
          temp = row.selectFirst(".zg"),
          description = row.selectFirst(".yg");
      if (period == null || temp == null || description == null) continue;
      String name = period.text().strip();
      if (!Arrays.asList(PERIODS).contains(name)) continue;
      String value = temp.text().strip().replace("°", "").replaceAll("^[+−-]0$", "0");
      String desc =
          description.text().strip().replaceFirst("(?iu)^(ночью|утром|днем|днём|вечером)\\s*", "");
      if (!value.isEmpty() && !desc.isEmpty()) values.put(name, new String[] {desc, value});
    }
    List<String> descriptions = new ArrayList<>(),
        temperatures = new ArrayList<>(),
        weatherParts = new ArrayList<>(),
        tempParts = new ArrayList<>();
    for (int i = 0; i < PERIODS.length; i++)
      if (values.containsKey(PERIODS[i])) {
        String[] v = values.get(PERIODS[i]);
        descriptions.add(v[0]);
        temperatures.add(v[1]);
        weatherParts.add(PREFIXES[i] + " " + v[0]);
        tempParts.add(PREFIXES[i] + " " + v[1]);
      }
    if (descriptions.isEmpty()) return new WeatherSummary("", "");
    return new WeatherSummary(
        new HashSet<>(descriptions).size() == 1
            ? descriptions.get(0)
            : String.join(", ", weatherParts),
        new HashSet<>(temperatures).size() == 1
            ? temperatures.get(0)
            : String.join(", ", tempParts));
  }

  public static WeatherSummary fetch(String city, LocalDate date, ZoneId zone) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(uri(city, date).toASCIIString()).openConnection();
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("User-Agent", "ObsNotes/Android");
    try {
      if (connection.getResponseCode() != 200)
        throw new IOException("nuipogoda: HTTP " + connection.getResponseCode());
      try (InputStream in = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Загрузка отменена");
          if (out.size() + count > 8 * 1024 * 1024)
            throw new IOException("Слишком большой ответ nuipogoda");
          out.write(buffer, 0, count);
        }
        WeatherSummary result = parse(out.toString(StandardCharsets.UTF_8.name()), date, zone);
        if (result.empty()) throw new IOException("Нет данных nuipogoda за " + date);
        return result;
      }
    } finally {
      connection.disconnect();
    }
  }
}
