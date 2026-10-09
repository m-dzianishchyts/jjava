package org.dflib.jjava.jupyter.kernel.display.protocol;

import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DisplayEncodingTest {

    @Test
    void multilineNonAsciiAndPngSurviveRoundTripUnchanged() {
        String multiline = "line one\nline two\r\nend";
        String nonAscii = "héllo — 日本語 🎉";
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0A, 0x1A, 0x0A});

        DisplayData data = new DisplayData(multiline);
        data.putData("text/x-non-ascii", nonAscii);
        data.putData("image/png", png);

        String line = DisplayProtocol.encode(DisplayRequest.of(DisplayRequest.Operation.DISPLAY, "id", data));
        DisplayData decoded = DisplayProtocol.decode(line).toDisplayData();

        assertEquals(multiline, decoded.getData().get("text/plain"));
        assertEquals(nonAscii, decoded.getData().get("text/x-non-ascii"));
        assertEquals(png, decoded.getData().get("image/png"));
    }

    @Test
    void encodedRequestNeverContainsRawLineFeed() {
        DisplayData data = new DisplayData("a\nb\nc");
        String line = DisplayProtocol.encode(DisplayRequest.of(DisplayRequest.Operation.DISPLAY, "id", data));

        assertFalse(line.contains("\n"), line);
    }
}
