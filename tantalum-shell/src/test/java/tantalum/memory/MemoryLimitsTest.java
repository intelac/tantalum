package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MemoryLimitsTest {

    @Test
    void memoryLimitsHaveDefaultsAndMustBePositive() {
        MemoryLimits memory = MemoryLimits.defaults();
        assertEquals(200, memory.maxEntries());
        assertEquals(16 * 1024, memory.maxIndexBytes());
        assertEquals(8 * 1024, memory.maxEntryBytes());
        assertThrows(IllegalArgumentException.class, () -> new MemoryLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryLimits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryLimits(1, 1, -1));
    }
}
