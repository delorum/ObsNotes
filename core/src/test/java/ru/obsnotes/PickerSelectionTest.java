package ru.obsnotes;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class PickerSelectionTest {
  @Test
  public void checkboxOrderSurvivesRemovalAndRecheckingAppends() {
    var s = new PickerSelection<String>();
    s.toggle("B");
    s.toggle("A");
    s.toggle("C");
    assertEquals(List.of("B", "A", "C"), s.selected());
    s.toggle("B");
    assertEquals(List.of("A", "C"), s.selected());
    s.toggle("B");
    assertEquals(List.of("A", "C", "B"), s.selected());
  }

  @Test
  public void quickChoiceKeepsStagedOrderWithoutDuplicates() {
    var s = new PickerSelection<>(List.of("B", "A"));
    assertEquals(List.of("B", "A"), s.choose("B"));
    assertEquals(List.of("B", "A", "C"), s.choose("C"));
    var snapshot = s.selected();
    s.toggle("A");
    assertEquals(List.of("B", "A", "C"), snapshot);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add("D"));
  }
}
