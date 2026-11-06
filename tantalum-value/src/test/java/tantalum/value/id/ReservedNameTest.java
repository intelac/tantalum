package tantalum.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** windows-server 需求 3.1–3.3：保留设备名带不带扩展名、不分大小写都算；只是像的名字不算。 */
class ReservedNameTest {

    @ParameterizedTest
    @CsvSource({
            "con, true", "NUL, true", "nul.jsonl, true", "com0, true", "lpt9.md, true", "Aux, true", "prn.tar.gz, true",
            "console, false", "con-1, false", "nul_, false", "com10, false", "lpt, false", "acon, false", "%6eul.jsonl, false"
    })
    void reservedDeviceNamesAreRecognised(String name, boolean reserved) {
        assertEquals(reserved, ReservedName.matches(name), name);
    }
}
