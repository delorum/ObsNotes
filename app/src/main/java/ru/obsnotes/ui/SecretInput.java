package ru.obsnotes.ui;

import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import android.widget.EditText;
import ru.obsnotes.SecretText;

/** Locked payloads never enter the visible/accessibility text or the clipboard. */
final class SecretInput {
  private SecretInput() {}

  static void protect(EditText input) {
    input.setSaveEnabled(false);
    input.setFilters(
        new InputFilter[] {
          (source, start, end, dest, dstart, dend) -> {
            String before = dest.toString();
            int at = 0;
            while ((at = before.indexOf(SecretText.PLACEHOLDER, at)) >= 0) {
              int limit = at + SecretText.PLACEHOLDER.length();
              boolean overlap =
                  dstart < limit && dend > at || dstart == dend && dstart > at && dstart < limit;
              if (overlap && !(dstart <= at && dend >= limit))
                return dest.subSequence(dstart, dend);
              at = limit;
            }
            return null;
          }
        });
  }

  static void decorate(Editable text) {
    for (var span : text.getSpans(0, text.length(), StyleSpan.class)) text.removeSpan(span);
    String value = text.toString();
    int at = 0;
    while ((at = value.indexOf(SecretText.PLACEHOLDER, at)) >= 0) {
      int end = at + SecretText.PLACEHOLDER.length();
      text.setSpan(new ForegroundColorSpan(Ui.MUTED), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(new StyleSpan(Typeface.ITALIC), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      at = end;
    }
  }
}
