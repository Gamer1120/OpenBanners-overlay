package org.openbanners.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The items of the mission card, as stored in the settings: a comma-separated list of the shown items in order. */
final class CardItems {
    static final String MINUS = "minus";
    static final String COUNTER = "counter";
    static final String PLUS = "plus";
    static final String NEXT = "next";
    static final List<String> ALL = Arrays.asList(MINUS, COUNTER, PLUS, NEXT);
    static final String DEFAULT = String.join(",", ALL);

    static final String LAYOUT_TWO_ROWS = "two_rows";
    static final String LAYOUT_WIDE = "wide";
    static final String LAYOUT_TALL = "tall";

    private CardItems() {
    }

    /** The shown items in order; unknown and duplicate entries are dropped, and an empty list falls back to the default. */
    static List<String> parse(String value) {
        List<String> items = new ArrayList<>();
        if (value != null) {
            for (String item : value.split(",")) {
                String trimmed = item.trim();
                if (ALL.contains(trimmed) && !items.contains(trimmed)) items.add(trimmed);
            }
        }
        return items.isEmpty() ? new ArrayList<>(ALL) : items;
    }
}
