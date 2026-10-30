package org.openbanners.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The items of the mission card, as stored in the settings: a comma-separated list of all items in order, where a
 * hidden item has a leading "-" (e.g. {@code minus,counter,plus,next,-close,done}).
 * <p>
 * Close and "Close and mark as done" only appear on the last mission; there, Start / Next is hidden (on the last
 * mission it used to turn into Close). Before version 48 the list held only the four always-present items and only
 * the shown ones: those values are migrated by {@link #parseConfig(String)}.
 */
final class CardItems {
    static final String MINUS = "minus";
    static final String COUNTER = "counter";
    static final String PLUS = "plus";
    static final String NEXT = "next";
    /** Last mission only: closes the overlay (what Start / Next did on the last mission). */
    static final String CLOSE = "close";
    /** Last mission only: closes the overlay and marks the banner as done on OpenBanners. Gets a line of its own. */
    static final String DONE = "done";
    static final List<String> ALL = Arrays.asList(MINUS, COUNTER, PLUS, NEXT, CLOSE, DONE);
    /** Items that only appear on the last mission. */
    static final List<String> LAST_MISSION_ONLY = Arrays.asList(CLOSE, DONE);
    /** Marks a hidden item in the stored list. */
    static final String HIDDEN = "-";
    static final String DEFAULT = String.join(",", ALL);

    static final String LAYOUT_TWO_ROWS = "two_rows";
    static final String LAYOUT_WIDE = "wide";
    static final String LAYOUT_TALL = "tall";

    private CardItems() {
    }

    /** All items in their order, and which of them are shown. */
    static final class Config {
        final List<String> order;
        final Set<String> shown;

        Config(List<String> order, Set<String> shown) {
            this.order = Collections.unmodifiableList(new ArrayList<>(order));
            this.shown = Collections.unmodifiableSet(new LinkedHashSet<>(shown));
        }

        /** The shown items in order. */
        List<String> shownItems() {
            List<String> items = new ArrayList<>();
            for (String item : order) {
                if (shown.contains(item)) items.add(item);
            }
            return items;
        }

        /** The stored form, see {@link CardItems}. */
        String format() {
            List<String> tokens = new ArrayList<>();
            for (String item : order) tokens.add(shown.contains(item) ? item : HIDDEN + item);
            return String.join(",", tokens);
        }
    }

    static boolean isLastMissionOnly(String item) {
        return LAST_MISSION_ONLY.contains(item);
    }

    /**
     * Reads a stored value. Unknown and duplicate entries are dropped. Values from before version 48 (without Close
     * and "Close and mark as done") keep looking like they did: the always-present items that are missing were hidden,
     * Close takes the place of Start / Next, and "Close and mark as done" goes below everything, both only if Start /
     * Next is shown (else they weren't there either). A value without any of minus, counter, plus or Start / Next
     * shown falls back to the default.
     */
    static Config parseConfig(String value) {
        List<String> order = new ArrayList<>();
        Set<String> shown = new LinkedHashSet<>();
        if (value != null) {
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                boolean hidden = trimmed.startsWith(HIDDEN);
                String item = hidden ? trimmed.substring(HIDDEN.length()).trim() : trimmed;
                if (!ALL.contains(item) || order.contains(item)) continue;
                order.add(item);
                if (!hidden) shown.add(item);
            }
        }
        boolean anyAlwaysShown = false;
        for (String item : shown) anyAlwaysShown |= !isLastMissionOnly(item);
        if (!anyAlwaysShown) return defaultConfig();

        for (String item : ALL) {
            if (!isLastMissionOnly(item) && !order.contains(item)) order.add(item); // was hidden
        }
        boolean nextShown = shown.contains(NEXT);
        if (!order.contains(CLOSE)) {
            order.add(order.indexOf(NEXT) + 1, CLOSE);
            if (nextShown) shown.add(CLOSE);
        }
        if (!order.contains(DONE)) {
            order.add(DONE);
            if (nextShown) shown.add(DONE);
        }
        return new Config(order, shown);
    }

    static Config defaultConfig() {
        return new Config(ALL, new LinkedHashSet<>(ALL));
    }

    /** The shown items in order (see {@link #parseConfig(String)}). */
    static List<String> parse(String value) {
        return parseConfig(value).shownItems();
    }

    /** The stored value in today's form (migrated and cleaned up). */
    static String normalize(String value) {
        return parseConfig(value).format();
    }

    /** Whether {@code value} only contains known items (each optionally marked hidden). */
    static boolean isValid(String value) {
        if (value == null) return false;
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.startsWith(HIDDEN)) trimmed = trimmed.substring(HIDDEN.length()).trim();
            if (!ALL.contains(trimmed)) return false;
        }
        return true;
    }

    /** Whether the value uses something apps before version 48 can't read (Close items or hidden markers). */
    static boolean needsNewFormat(String value) {
        if (value == null) return false;
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.startsWith(HIDDEN) || isLastMissionOnly(trimmed)) return true;
        }
        return false;
    }

    /**
     * The items on the card right now, in order: Start / Next everywhere but on the last mission, Close on the last
     * mission, "Close and mark as done" when the banner can be marked as done (last mission of a banner or plan).
     */
    static List<String> visible(Config config, boolean lastMission, boolean canMarkDone) {
        List<String> items = new ArrayList<>();
        for (String item : config.shownItems()) {
            if (item.equals(NEXT) && lastMission) continue;
            if (item.equals(CLOSE) && !lastMission) continue;
            if (item.equals(DONE) && !canMarkDone) continue;
            items.add(item);
        }
        return items;
    }

    /** One line of the card: either a row of items side by side, or a single item over the full width. */
    static final class Line {
        final List<String> items;
        final boolean row;

        Line(List<String> items, boolean row) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.row = row;
        }

        @Override
        public String toString() {
            return (row ? "row" : "full") + items;
        }
    }

    /**
     * Whether the card would be empty on some mission with these items shown: before the last mission only −, the
     * counter, + and Start / Next show; on the last mission −, the counter, + and the two Close buttons.
     */
    static boolean leavesCardEmpty(Set<String> shown) {
        boolean always = shown.contains(MINUS) || shown.contains(COUNTER) || shown.contains(PLUS);
        boolean beforeLast = always || shown.contains(NEXT);
        boolean last = always || shown.contains(CLOSE) || shown.contains(DONE);
        return !beforeLast || !last;
    }

    private static boolean isNextOrClose(String item) {
        return NEXT.equals(item) || CLOSE.equals(item);
    }

    /**
     * Arranges the card into lines for a layout and mission. Every item keeps its spot on every mission; an item that
     * doesn't show on this mission just leaves it out, and Start / Next and Close directly next to each other share one
     * spot (they never show together). So by default Close appears exactly where Start / Next was.
     * <ul>
     * <li>Two rows: all spots but the last side by side, the last one full width below (the original card).</li>
     * <li>Wide: one row. Tall: one item per line.</li>
     * <li>"Close and mark as done" always gets a full-width line of its own: at the top if it comes first, at the
     * bottom if it comes last, else (two rows) between the row and the last spot, or (wide) below the row.</li>
     * </ul>
     */
    static List<Line> arrange(Config config, String layout, boolean lastMission, boolean canMarkDone) {
        List<String> visible = visible(config, lastMission, canMarkDone);
        List<Line> lines = new ArrayList<>();
        if (LAYOUT_TALL.equals(layout)) {
            for (String item : visible) lines.add(new Line(Collections.singletonList(item), false));
            return lines;
        }
        // Spots of the shown items (without "Close and mark as done"), and how many come before that one.
        List<List<String>> spots = new ArrayList<>();
        int spotsBeforeDone = -1;
        String previous = null;
        for (String item : config.shownItems()) {
            if (item.equals(DONE)) {
                spotsBeforeDone = spots.size();
                continue; // not a spot: Start / Next and Close around it still share one
            }
            if (isNextOrClose(item) && isNextOrClose(previous)) {
                spots.get(spots.size() - 1).add(item);
                // "Close and mark as done" between them: it goes above that shared spot.
                if (spotsBeforeDone == spots.size()) spotsBeforeDone--;
            } else {
                spots.add(new ArrayList<>(Collections.singletonList(item)));
            }
            previous = item;
        }
        List<String> top;
        List<String> bottom;
        if (LAYOUT_WIDE.equals(layout) || spots.size() <= 1) {
            top = flatten(spots);
            bottom = Collections.emptyList();
        } else {
            top = flatten(spots.subList(0, spots.size() - 1));
            bottom = spots.get(spots.size() - 1);
        }
        top.retainAll(visible);
        bottom = new ArrayList<>(bottom);
        bottom.retainAll(visible);
        boolean doneShows = visible.contains(DONE);
        Line done = new Line(Collections.singletonList(DONE), false);
        if (doneShows && spotsBeforeDone == 0) lines.add(done);
        if (!top.isEmpty()) {
            // A single spot (or wide with one item) fills the width like a bottom item.
            boolean row = LAYOUT_WIDE.equals(layout) || spots.size() > 1;
            lines.add(new Line(top, row));
        }
        if (doneShows && spotsBeforeDone > 0 && spotsBeforeDone < spots.size()) lines.add(done);
        if (!bottom.isEmpty()) lines.add(new Line(bottom, false));
        if (doneShows && spotsBeforeDone > 0 && spotsBeforeDone == spots.size()) lines.add(done);
        return lines;
    }

    private static List<String> flatten(List<List<String>> spots) {
        List<String> items = new ArrayList<>();
        for (List<String> spot : spots) items.addAll(spot);
        return items;
    }
}
