package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.shell.boot.TantalumProperties.Loop;

class TantalumPropertiesTest {

    @Test
    void defaultsSatisfyTheFormulas() {
        Loop loop = Loop.defaults();
        assertEquals(64, loop.maxActiveRuns());
        assertEquals(10, loop.maxParallelTools());
        assertEquals(1024, loop.outputRingSize());
        assertEquals(1024, loop.inputRingSize());
        assertEquals(640, loop.internalReserve());
    }

    @Test
    void outputRingMustHoldEveryCommand() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Loop(64, 10, 8, 256, 1024, 512));
        assertTrue(failure.getMessage().contains("outputRingSize"));
    }

    @Test
    void inputRingMustHoldInternalResultsAndExternalLimit() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Loop(64, 10, 8, 256, 512, 1024));
        assertTrue(failure.getMessage().contains("inputRingSize"));
    }

    @Test
    void ringSizesMustBePowersOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 1, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 1, 1024, 1000));
    }

    @Test
    void memoryLimitsHaveDefaultsAndMustBePositive() {
        TantalumProperties.Memory memory = TantalumProperties.Memory.defaults();
        assertEquals(200, memory.maxEntries());
        assertEquals(16 * 1024, memory.maxIndexBytes());
        assertEquals(8 * 1024, memory.maxEntryBytes());
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Memory(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Memory(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Memory(1, 1, -1));
    }

    @Test
    void blankKnowledgeDirMeansNoSharedLayer() {
        assertTrue(TantalumProperties.Knowledge.none().path().isEmpty());
        assertTrue(new TantalumProperties.Knowledge("  ", 10).path().isEmpty());
        assertTrue(new TantalumProperties.Knowledge(null, 10).path().isEmpty());
        assertEquals(java.nio.file.Path.of("/srv/knowledge"), new TantalumProperties.Knowledge("/srv/knowledge", 10).path().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new TantalumProperties.Knowledge("/x", 0));
    }

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Loop(0, 1, 1, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 0, 1, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 0, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Loop(1, 1, 1, 0, 1024, 1024));
    }
}
