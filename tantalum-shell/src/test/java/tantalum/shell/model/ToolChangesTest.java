package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.model.ToolChangeBlock;

/** one-line-journal 需求 4.3：不支持追加式工具变化的模型，每次变化都在文字里重申仍不能用的工具。 */
class ToolChangesTest {

    @Test
    void theTextRepeatsToolsThatAreStillUnavailable() {
        assertEquals(ToolChanges.PREFIX + "Your tools changed. No longer available: read_file. Still unavailable: write_file.",
                ToolChanges.describe(new ToolChangeBlock(List.of(), List.of("read_file"), List.of("write_file"))));
        assertEquals(ToolChanges.PREFIX + "Your tools changed. Now available: grep.",
                ToolChanges.describe(new ToolChangeBlock(List.of("grep"), List.of())));
    }
}
