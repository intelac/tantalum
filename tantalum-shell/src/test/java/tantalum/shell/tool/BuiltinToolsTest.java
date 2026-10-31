package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

class BuiltinToolsTest {

    @TempDir
    Path temp;

    private Path root;
    private ReadFileTool read;
    private GrepTool grep;
    private ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root"));
        Files.writeString(root.resolve("a.cbl"), "COMPUTE INTEREST = 1\nMOVE X TO Y\n");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub/b.cbl"), "COMPUTE TAX\n; rm -rf /tmp/x\n");
        Files.writeString(temp.resolve("secret.txt"), "COMPUTE SECRET");
        context = new ToolContext(new Principal("t"), new Workspace(root));
        read = new ReadFileTool();
        grep = new GrepTool();
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    @Test
    void readReturnsTheFile() throws Exception {
        ToolOutput output = read.run(context, args("path", "a.cbl"));
        assertFalse(output.error());
        assertTrue(output.text().contains("COMPUTE INTEREST"));
    }

    @Test
    void readRejectsEscapes() throws Exception {
        ToolOutput output = read.run(context, args("path", "../secret.txt"));
        assertTrue(output.error());
        assertFalse(output.text().contains("SECRET"));
    }

    @Test
    void readReportsMissingArgument() throws Exception {
        assertTrue(read.run(context, args()).error());
    }

    @Test
    void readReportsADirectoryAsAnError() throws Exception {
        assertTrue(read.run(context, args("path", "sub")).error());
    }

    @Test
    void grepFindsMatchesWithFileAndLine() throws Exception {
        ToolOutput output = grep.run(context, args("pattern", "COMPUTE"));
        assertFalse(output.error());
        assertTrue(output.text().contains("a.cbl:1:COMPUTE INTEREST"), output.text());
        assertTrue(output.text().contains("b.cbl:1:COMPUTE TAX"), output.text());
        assertFalse(output.text().contains("SECRET"));
    }

    @Test
    void grepTreatsShellCharactersAsPlainPattern() throws Exception {
        ToolOutput output = grep.run(context, args("pattern", "; rm -rf /tmp/x"));
        assertFalse(output.error());
        assertTrue(output.text().contains("b.cbl:2"), output.text());
        assertFalse(Files.exists(Path.of("/tmp/x-should-not-exist")));
    }

    @Test
    void grepCanBeLimitedToASubdirectory() throws Exception {
        ToolOutput output = grep.run(context, args("pattern", "COMPUTE", "path", "sub"));
        assertFalse(output.text().contains("a.cbl"));
    }

    @Test
    void grepRejectsEscapesAndBadPatterns() throws Exception {
        assertTrue(grep.run(context, args("pattern", "x", "path", "..")).error());
        assertTrue(grep.run(context, args("pattern", "(")).error());
        assertTrue(grep.run(context, args()).error());
    }

    @Test
    void grepSkipsSymlinksLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("link.txt"), temp.resolve("secret.txt"));
        assertFalse(grep.run(context, args("pattern", "SECRET")).text().contains("SECRET"));
    }

    @Test
    void grepReportsNoMatches() throws Exception {
        ToolOutput output = grep.run(context, args("pattern", "NOPE"));
        assertFalse(output.error());
        assertEquals("No matches.", output.text());
    }

    @Test
    void builtinsAreSafeToRunTogether() {
        assertTrue(read.isConcurrencySafe(args()));
        assertTrue(grep.isConcurrencySafe(args()));
    }
}
