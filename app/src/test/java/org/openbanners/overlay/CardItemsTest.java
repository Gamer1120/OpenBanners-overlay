package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class CardItemsTest {
    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }

    private static String lines(String value, String layout, boolean lastMission, boolean canMarkDone) {
        return CardItems.arrange(CardItems.parseConfig(value), layout, lastMission, canMarkDone).toString();
    }

    @Test
    public void defaultShowsEverythingInTodaysOrder() {
        CardItems.Config config = CardItems.parseConfig(CardItems.DEFAULT);
        assertEquals(list("minus", "counter", "plus", "next", "close", "done"), config.shownItems());
        assertEquals(CardItems.DEFAULT, config.format());
    }

    @Test
    public void defaultCardLooksLikeBefore() {
        // Other missions: - 1/5 + in a row, Next below.
        assertEquals("[row[minus, counter, plus], full[next]]", lines(CardItems.DEFAULT, CardItems.LAYOUT_TWO_ROWS, false, false));
        // Last mission: Close where Next was, Close and mark as done below.
        assertEquals("[row[minus, counter, plus], full[close], full[done]]", lines(CardItems.DEFAULT, CardItems.LAYOUT_TWO_ROWS, true, true));
        assertEquals("[row[minus, counter, plus, close], full[done]]", lines(CardItems.DEFAULT, CardItems.LAYOUT_WIDE, true, true));
        assertEquals("[full[minus], full[counter], full[plus], full[close], full[done]]", lines(CardItems.DEFAULT, CardItems.LAYOUT_TALL, true, true));
        // Last mission of something that can't be marked as done: only Close.
        assertEquals("[row[minus, counter, plus], full[close]]", lines(CardItems.DEFAULT, CardItems.LAYOUT_TWO_ROWS, true, false));
    }

    @Test
    public void nothingSavedIsTheDefault() {
        assertEquals(CardItems.DEFAULT, CardItems.normalize(null));
        assertEquals(CardItems.DEFAULT, CardItems.normalize(""));
    }

    @Test
    public void oldDefaultLayoutGetsBothButtonsAtTheirDefaultSpots() {
        assertEquals(CardItems.DEFAULT, CardItems.normalize("minus,counter,plus,next"));
    }

    @Test
    public void oldCustomLayoutKeepsCloseInNextsPlace() {
        // Saved in version 47: Next first, then the rest. Close took Next's place, Close and mark as done was below.
        String old = "next,minus,counter,plus";
        assertEquals("next,close,minus,counter,plus,done", CardItems.normalize(old));
        assertEquals("[row[next, minus, counter], full[plus]]", lines(old, CardItems.LAYOUT_TWO_ROWS, false, false));
        assertEquals("[row[close, minus, counter], full[plus], full[done]]", lines(old, CardItems.LAYOUT_TWO_ROWS, true, true));
    }

    @Test
    public void oldLayoutWithHiddenItemsKeepsThemHidden() {
        // Version 47 only stored the shown items; + was hidden.
        CardItems.Config config = CardItems.parseConfig("counter,minus,next");
        assertEquals(list("counter", "minus", "next", "close", "done"), config.shownItems());
        assertEquals("counter,minus,next,close,-plus,done", config.format());
    }

    @Test
    public void oldLayoutWithoutNextHadNoCloseButtons() {
        // Without the Next/Close button, version 47 showed neither Close nor Close and mark as done.
        CardItems.Config config = CardItems.parseConfig("minus,counter,plus");
        assertEquals(list("minus", "counter", "plus"), config.shownItems());
        assertEquals("minus,counter,plus,-next,-close,-done", config.format());
        assertEquals("[row[minus, counter], full[plus]]", lines("minus,counter,plus", CardItems.LAYOUT_TWO_ROWS, true, true));
    }

    @Test
    public void hiddenButtonsStayHidden() {
        String saved = new CardItems.Config(CardItems.ALL, new HashSet<>(list("minus", "counter", "plus", "next", "done"))).format();
        assertEquals("minus,counter,plus,next,-close,done", saved);
        CardItems.Config config = CardItems.parseConfig(saved);
        assertFalse(config.shown.contains("close"));
        assertTrue(config.shown.contains("done"));
        assertEquals(saved, config.format());
        // Last mission without Close: the row stays, Close and mark as done below it.
        assertEquals("[row[minus, counter, plus], full[done]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, true, true));
    }

    @Test
    public void hidingBothButtonsPersists() {
        String saved = "minus,counter,plus,next,-close,-done";
        assertEquals(saved, CardItems.normalize(saved));
        assertEquals("[row[minus, counter, plus]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, true, true));
        // Other missions are unaffected.
        assertEquals("[row[minus, counter, plus], full[next]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, false, false));
    }

    @Test
    public void reorderingPersists() {
        String saved = new CardItems.Config(list("done", "minus", "counter", "plus", "close", "next"), new HashSet<>(CardItems.ALL)).format();
        assertEquals("done,minus,counter,plus,close,next", saved);
        assertEquals(list("done", "minus", "counter", "plus", "close", "next"), CardItems.parse(saved));
        // Close and mark as done on top, Close as the last item below the row.
        assertEquals("[full[done], row[minus, counter, plus], full[close]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, true, true));
        assertEquals("[row[minus, counter, plus], full[next]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, false, false));
    }

    @Test
    public void closeCanSitInTheRow() {
        String saved = "minus,counter,close,plus,next,done";
        // Close keeps its own spot in the row; Start / Next keeps the line below (empty on the last mission).
        assertEquals("[row[minus, counter, close, plus], full[done]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, true, true));
        assertEquals("[row[minus, counter, plus], full[next]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, false, false));
    }

    @Test
    public void doneInTheMiddleGoesBetweenRowAndLastItem() {
        String saved = "minus,counter,plus,next,done,close";
        assertEquals("[row[minus, counter, plus], full[done], full[close]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, true, true));
        // Start / Next and Close still share the spot below the row.
        assertEquals("[row[minus, counter, plus], full[next]]", lines(saved, CardItems.LAYOUT_TWO_ROWS, false, false));
        assertEquals("[row[minus, counter, plus, close], full[done]]", lines(saved, CardItems.LAYOUT_WIDE, true, true));
        assertEquals("[full[minus], full[counter], full[plus], full[done], full[close]]", lines(saved, CardItems.LAYOUT_TALL, true, true));
    }

    @Test
    public void onlyCloseButtonsShownFallsBackToDefault() {
        assertEquals(CardItems.DEFAULT, CardItems.normalize("-minus,-counter,-plus,-next,close,done"));
        assertEquals(CardItems.DEFAULT, CardItems.normalize("close"));
    }

    @Test
    public void unknownAndDuplicateEntriesAreDropped() {
        assertEquals("plus,minus,counter,next,close,done", CardItems.normalize("plus, foo ,minus,plus,counter,next"));
        assertEquals("minus,-counter,plus,next,close,done", CardItems.normalize("minus,-counter,plus,-counter,next,close,done"));
    }

    @Test
    public void cardNeverEmpty() {
        assertFalse(CardItems.leavesCardEmpty(new HashSet<>(CardItems.ALL)));
        assertFalse(CardItems.leavesCardEmpty(new HashSet<>(list("counter"))));
        assertFalse(CardItems.leavesCardEmpty(new HashSet<>(list("next", "close"))));
        assertFalse(CardItems.leavesCardEmpty(new HashSet<>(list("next", "done"))));
        // Nothing left on the last mission.
        assertTrue(CardItems.leavesCardEmpty(new HashSet<>(list("next"))));
        // Nothing left before the last mission.
        assertTrue(CardItems.leavesCardEmpty(new HashSet<>(list("close", "done"))));
    }

    @Test
    public void validation() {
        assertTrue(CardItems.isValid("minus,counter,plus,next"));
        assertTrue(CardItems.isValid("minus,-counter,plus,next,-close,done"));
        assertFalse(CardItems.isValid("minus,bogus"));
        assertFalse(CardItems.isValid("-"));
        assertFalse(CardItems.needsNewFormat("next,minus,counter,plus"));
        assertTrue(CardItems.needsNewFormat("minus,counter,plus,next,close,done"));
        assertTrue(CardItems.needsNewFormat("minus,-counter"));
    }
}
