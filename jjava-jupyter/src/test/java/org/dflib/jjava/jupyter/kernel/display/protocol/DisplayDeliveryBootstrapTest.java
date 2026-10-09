package org.dflib.jjava.jupyter.kernel.display.protocol;

import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.dflib.jjava.jupyter.kernel.display.protocol.uds.UnixDomainDisplayDelivery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.dflib.jjava.jupyter.kernel.display.protocol.DisplayDeliveryException.Kind.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayDeliveryBootstrapTest {

    private static final DisplayRequest REQUEST = DisplayRequest.of(DisplayRequest.Operation.DISPLAY, "id", new DisplayData("x"));

    @Test
    void noSocketPropertyFailsEveryDeliveryUnavailable() {
        DisplayDelivery unavailable = DisplayDeliveryBootstrap.fromSocketPath(null);
        DisplayDeliveryException e = assertThrows(DisplayDeliveryException.class, () -> unavailable.deliver(REQUEST));
        assertEquals(UNAVAILABLE, e.kind());
    }

    @Test
    void socketPropertySelectsUdsDelivery() {
        assertInstanceOf(UnixDomainDisplayDelivery.class, DisplayDeliveryBootstrap.fromSocketPath("/tmp/x.sock"));
    }

    @Test
    void installReturnsPreviousAndIsUsedForSubsequentCalls() {
        DisplayDelivery previous = DisplayDeliveryBootstrap.current();
        List<DisplayRequest> seen = new ArrayList<>();
        try {
            assertSame(previous, DisplayDeliveryBootstrap.install(seen::add));
            DisplayDeliveryBootstrap.current().deliver(REQUEST);
            assertEquals(1, seen.size());
        } finally {
            DisplayDeliveryBootstrap.install(previous);
        }
    }
}
