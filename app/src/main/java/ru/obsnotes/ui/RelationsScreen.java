package ru.obsnotes.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import ru.obsnotes.Events;
import ru.obsnotes.FuzzySearch;
import ru.obsnotes.RelationSearch;

/** Shows the event, then its additions as they arrive. The list recycles visible rows. */
public final class RelationsScreen {
  private record Row(RelationSearch.Related item, String location, String preview) {
    Row(RelationSearch.Related item) {
      this(item, item.location(), item.preview());
    }

    String searchable() {
      return location + "\n" + preview;
    }
  }

  private final Ui ui;
  private final ListView list;
  private final TextView status;
  private final List<Row> additions = new ArrayList<>();
  private final List<Row> visible = new ArrayList<>();
  private final Results adapter = new Results();
  private Row root;
  private String query = "";

  public RelationsScreen(
      Ui ui,
      List<RelationSearch.RootChoice> roots,
      Events.Parent active,
      Runnable back,
      Consumer<Events.Parent> chooseRoot,
      Consumer<RelationSearch.Related> edit) {
    this.ui = ui;
    ui.hideKeyboard();
    ui.page("Связи", back);
    ui.root.removeView(ui.scroll);

    var controls = ui.body = ui.column();
    controls.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4));
    ui.root.addView(controls);
    if (roots.size() > 1) {
      var picker = new Spinner(ui.activity);
      picker.setContentDescription("Корень события");
      var labels =
          new ArrayAdapter<>(
              ui.activity,
              android.R.layout.simple_spinner_item,
              roots.stream().map(RelationSearch.RootChoice::label).toList());
      labels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
      picker.setAdapter(labels);
      int position = 0;
      while (!roots.get(position).target().equals(active)) position++;
      picker.setSelection(position);
      picker.setOnItemSelectedListener(
          new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> view, View item, int index, long id) {
              chooseRoot.accept(roots.get(index).target());
            }

            public void onNothingSelected(AdapterView<?> view) {}
          });
      controls.addView(picker, new LinearLayout.LayoutParams(-1, ui.dp(44)));
    }
    ui.field(
        controls,
        "Поиск дополнений",
        "",
        false,
        value -> {
          query = value.strip();
          filter();
          listToStart();
        });
    status = ui.text("Поиск корня…", 12, Ui.MUTED);
    status.setPadding(0, ui.dp(4), 0, ui.dp(4));
    controls.addView(status);

    list = new ListView(ui.activity);
    list.setContentDescription("Список связей");
    list.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
    list.setClipToPadding(false);
    list.setDivider(null);
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent, view, at, id) -> edit.accept(visible.get(at).item()));
    ui.root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void listToStart() {
    list.setSelection(0);
  }

  public void reset() {
    root = null;
    additions.clear();
    visible.clear();
    adapter.notifyDataSetChanged();
    listToStart();
    status.setText("Поиск корня…");
    ui.error(null);
  }

  public void update(RelationSearch.Update result) {
    if (result.root() != null) {
      root = new Row(result.root());
      visible.add(root);
    }
    if (!result.additions().isEmpty()) {
      var added = result.additions().stream().map(Row::new).toList();
      additions.addAll(added);
      if (query.isEmpty()) visible.addAll(added);
      else {
        filter();
        updateStatus(result);
        return;
      }
    }
    if (result.root() != null || !result.additions().isEmpty()) adapter.notifyDataSetChanged();
    updateStatus(result);
  }

  private void updateStatus(RelationSearch.Update result) {
    if (root == null) {
      status.setText("Корень не найден");
      return;
    }
    String message = "Дополнений: " + additions.size();
    if (!result.complete()) message += " · Проверено дней: " + result.checked();
    if (result.unreadable() > 0) message += " · Недоступно: " + result.unreadable();
    status.setText(message);
  }

  public void failed(String message) {
    status.setText(root == null ? "Корень недоступен" : "Поиск не завершён");
    ui.error(message);
  }

  private void filter() {
    visible.clear();
    if (root != null) visible.add(root);
    visible.addAll(
        query.isEmpty() ? additions : FuzzySearch.ranked(additions, query, Row::searchable));
    adapter.notifyDataSetChanged();
  }

  private final class Results extends BaseAdapter {
    public int getCount() {
      return visible.size();
    }

    public Row getItem(int position) {
      return visible.get(position);
    }

    public long getItemId(int position) {
      return position;
    }

    public View getView(int position, View recycled, ViewGroup parent) {
      var view = recycled == null ? new ResultView() : (ResultView) recycled;
      Row item = getItem(position);
      view.bind(item, item == root);
      return view;
    }
  }

  private final class ResultView extends LinearLayout {
    private final TextView location, preview;

    ResultView() {
      super(ui.activity);
      setOrientation(VERTICAL);
      setPadding(0, 0, 0, ui.dp(8));
      var card = ui.card(this);
      // The list item supplies the inter-card spacing and handles the click.
      ((LinearLayout.LayoutParams) card.getLayoutParams()).bottomMargin = 0;
      location = ui.bold("", 13, Ui.MUTED);
      preview = ui.text("", 16, Ui.INK);
      card.addView(location);
      card.addView(preview);
    }

    void bind(Row row, boolean root) {
      location.setText(root ? "Корень · " + row.location() : row.location());
      location.setTextColor(root ? Ui.ACCENT : Ui.MUTED);
      preview.setText(row.preview());
    }
  }
}
