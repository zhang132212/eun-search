package com.eunsearch.scan;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class ScanBoundsTest {
    private void validate(int x1, int y1, int z1, int x2, int y2, int z2) throws Throwable {
        Method method = RegionScanner.class.getDeclaredMethod("validateBounds",
                int.class, int.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        try { method.invoke(null, x1, y1, z1, x2, y2, z2); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @Test void alignedChunkLimitIsAccepted() {
        assertDoesNotThrow(() -> validate(0, 0, 0, 65535, 0, 0));
    }

    @Test void shiftedStripeCountsBothChunksAtZBoundary() {
        // The old extent formula counted 4096 chunks; this crosses 8192.
        assertThrows(IOException.class, () -> validate(0, 0, 15, 65535, 0, 16));
    }

    @Test void negativeBoundaryAlsoCountsBothChunks() {
        assertThrows(IOException.class, () -> validate(0, 0, -1, 65535, 0, 0));
    }

    @Test void shiftedXBoundaryExceedsLimit() {
        assertThrows(IOException.class, () -> validate(15, 0, 0, 65550, 0, 0));
    }

    @Test void invalidAndExtremeRangesAreRejectedWithoutOverflow() {
        assertThrows(IOException.class, () -> validate(1, 0, 0, 0, 0, 0));
        assertThrows(IOException.class, () -> validate(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
