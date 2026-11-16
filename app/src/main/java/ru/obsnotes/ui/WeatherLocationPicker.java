package ru.obsnotes.ui;

import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import java.util.function.Consumer;
import ru.obsnotes.*;

public final class WeatherLocationPicker {
  private WeatherLocationPicker() {}

  public static void show(Ui ui, String current, Consumer<String> select) {
    List<WeatherSummary.Location> cities = new ArrayList<>(WeatherSummary.LOCATIONS);
    cities.add(new WeatherSummary.Location("Другой город…", "custom"));
    SearchPicker.show(
        ui,
        "Город nuipogoda",
        cities,
        l -> l.name() + " · " + l.code(),
        false,
        cities.stream()
            .filter(l -> l.code().equals(current))
            .collect(java.util.stream.Collectors.toList()),
        ignored -> null,
        chosen -> {
          String city = chosen.get(0).code();
          if (!city.equals("custom")) {
            select.accept(city);
            return;
          }
          LinearLayout layout = ui.column();
          layout.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0);
          EditText code = ui.field(layout, "Код города, например vidnoe", "", false, null);
          var dialog =
              new MaterialAlertDialogBuilder(ui.activity)
                  .setTitle("Город nuipogoda")
                  .setView(layout)
                  .setPositiveButton("Выбрать", null)
                  .setNegativeButton("Отмена", null)
                  .create();
          dialog.show();
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v -> {
                    String value = code.getText().toString().strip().toLowerCase(Locale.ROOT);
                    try {
                      WeatherSummary.uri(value, java.time.LocalDate.now());
                      dialog.dismiss();
                      select.accept(value);
                    } catch (IllegalArgumentException error) {
                      code.setError(error.getMessage());
                    }
                  });
          ui.focusInput(code);
        });
  }
}
