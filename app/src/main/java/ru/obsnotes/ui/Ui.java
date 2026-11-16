package ru.obsnotes.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;
import java.util.function.Consumer;

/** Things palette and shared spacing. Icon buttons have centered vectors and 48 dp targets. */
public final class Ui {
  public static final int BG = 0xff1e232a,
      CARD = 0xff282e38,
      INK = 0xffe6e7ec,
      MUTED = 0xffa0abbd,
      ACCENT = 0xffb5a3e8,
      GREEN = 0xffa7cd65,
      RED = 0xffef737c;
  public final AppCompatActivity activity;
  public LinearLayout body, bar, root;
  public ScrollView scroll;
  private ProgressBar progress;
  private TextView error;

  public Ui(AppCompatActivity activity) {
    this.activity = activity;
  }

  public int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  public void focusInput(EditText field) {
    field.requestFocus();
    field.post(
        () -> {
          if (!field.isAttachedToWindow() || !field.hasFocus()) return;
          ((android.view.inputmethod.InputMethodManager)
                  activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
              .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
  }

  public void hideKeyboard() {
    var focus = activity.getCurrentFocus();
    if (focus != null)
      ((android.view.inputmethod.InputMethodManager)
              activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focus.getWindowToken(), 0);
  }

  /** Run scroll adjustments after new child positions have actually been laid out. */
  public void afterLayout(View view, Runnable action) {
    view.getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              public void onGlobalLayout() {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (view.isAttachedToWindow()) action.run();
              }
            });
    view.requestLayout();
  }

  public LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  public LinearLayout row() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setBaselineAligned(false);
    GradientDrawable spacer = new GradientDrawable();
    spacer.setColor(0);
    spacer.setSize(dp(8), 0);
    v.setDividerDrawable(spacer);
    v.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    return v;
  }

  public GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  public TextView text(String value, int size, int color) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(5), 0, dp(5));
    return t;
  }

  public TextView bold(String value, int size, int color) {
    TextView t = text(value, size, color);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  public void weighted(LinearLayout parent, View view) {
    parent.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
  }

  public MaterialButton button(String label, Runnable click) {
    MaterialButton b = new MaterialButton(activity);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(INK);
    b.setCornerRadius(dp(14));
    b.setBackgroundTintList(ColorStateList.valueOf(0xff343c4b));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  public MaterialButton icon(int resource, String description, Runnable click) {
    MaterialButton b = button("", click);
    b.setIcon(AppCompatResources.getDrawable(activity, resource));
    b.setIconTint(ColorStateList.valueOf(INK));
    b.setIconSize(dp(24));
    b.setIconPadding(0);
    b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
    b.setGravity(Gravity.CENTER);
    b.setPadding(0, 0, 0, 0);
    b.setInsetTop(0);
    b.setInsetBottom(0);
    b.setContentDescription(description);
    ViewCompat.setTooltipText(b, description);
    return b;
  }

  public void action(LinearLayout parent, int resource, String label, Runnable click) {
    parent.addView(icon(resource, label, click), new LinearLayout.LayoutParams(dp(48), dp(48)));
  }

  public void page(String title, Runnable back) {
    root = column();
    root.setBackgroundColor(BG);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars()
                      | WindowInsetsCompat.Type.ime()
                      | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    bar = row();
    bar.setPadding(dp(16), dp(4), dp(16), dp(4));
    if (back != null) action(bar, ru.obsnotes.R.drawable.ic_left, "Назад", back);
    weighted(bar, bold(title, 22, INK));
    root.addView(bar);
    progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
    progress.setVisibility(View.GONE);
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
    error = text("", 14, RED);
    error.setPadding(dp(20), dp(4), dp(20), dp(4));
    error.setVisibility(View.GONE);
    root.addView(error);
    scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    body = column();
    body.setPadding(dp(20), dp(8), dp(20), dp(24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    activity.setContentView(root);
    ViewCompat.requestApplyInsets(root);
  }

  public void error(String value) {
    error.setText(value);
    error.setVisibility(value == null ? View.GONE : View.VISIBLE);
  }

  public void busy(boolean value) {
    progress.setVisibility(value ? View.VISIBLE : View.GONE);
    enable(bar, !value);
    enable(body, !value);
  }

  public void loading(boolean value) {
    progress.setVisibility(value ? View.VISIBLE : View.GONE);
  }

  private void enable(View view, boolean value) {
    view.setEnabled(value);
    if (view instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), value);
  }

  public EditText field(
      LinearLayout parent, String label, String value, boolean multiline, Consumer<String> watch) {
    TextInputLayout box = new TextInputLayout(activity);
    box.setHint(label);
    box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    box.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
    box.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
    TextInputEditText edit = new TextInputEditText(box.getContext());
    edit.setTextColor(INK);
    edit.setTextSize(17);
    edit.setPadding(dp(16), dp(16), dp(16), dp(16));
    edit.setMinHeight(dp(56));
    edit.setSingleLine(!multiline);
    edit.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    if (multiline) {
      edit.setMinLines(5);
      edit.setGravity(Gravity.TOP | Gravity.START);
    }
    edit.setText(value);
    box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(8);
    parent.addView(box, params);
    if (watch != null) watch(edit, watch);
    return edit;
  }

  public EditText searchField(LinearLayout parent, String label, String value) {
    LinearLayout row = row(), input = column();
    EditText search = field(input, label, value, false, null);
    ((LinearLayout.LayoutParams) input.getChildAt(0).getLayoutParams()).bottomMargin = 0;
    weighted(row, input);
    action(
        row,
        ru.obsnotes.R.drawable.ic_clear,
        "Очистить поиск",
        () -> {
          search.setText("");
          focusInput(search);
        });
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.bottomMargin = dp(8);
    parent.addView(row, layout);
    return search;
  }

  public void watch(EditText field, Consumer<String> callback) {
    field.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            callback.accept(s.toString());
          }

          public void afterTextChanged(Editable s) {}
        });
  }

  public LinearLayout card(LinearLayout parent) {
    LinearLayout card = column();
    card.setBackground(background(CARD, 14));
    card.setPadding(dp(10), dp(8), dp(10), dp(8));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(8);
    parent.addView(card, p);
    return card;
  }
}
