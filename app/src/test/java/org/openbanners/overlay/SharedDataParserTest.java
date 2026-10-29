package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SharedDataParserTest {
    /** Opened by a link the app doesn't understand: nothing was shared, which must not crash. */
    @Test
    public void nothingSharedIsUnsupported() {
        SharedDataParser.ParsedData parsed = SharedDataParser.parse(null);
        assertEquals(SharedDataParser.ParsedDataType.invalid, parsed.type);
        assertNull(parsed.id);
        assertEquals(SharedDataParser.ParsedDataType.invalid, SharedDataParser.parse("").type);
        assertEquals(SharedDataParser.ParsedDataType.invalid, SharedDataParser.parse("   ").type);
    }
}
