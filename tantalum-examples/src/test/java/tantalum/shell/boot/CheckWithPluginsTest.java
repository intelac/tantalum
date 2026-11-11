package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.examples.boot.ExamplePlugins;
import tantalum.shell.cli.CliText;
import tantalum.value.text.Language;

/**
 * {@code check} 要装上类路径上以自动配置注册的插件：少了它们，插件做过决定的会话（比如每日额度拦下的一次提问）都报不一致。
 * 放在这个包里是因为命令只在包内可见，放在这个模块里是因为这里的类路径上才有插件 jar。
 */
class CheckWithPluginsTest {

    @TempDir
    Path dir;

    @Test
    void pluginAutoConfigurationsAreAmongTheSources() {
        assertTrue(RecordCheckCommand.sources().contains(ExamplePlugins.class), RecordCheckCommand.sources().toString());
        assertTrue(RecordCheckCommand.sources().stream().noneMatch(source -> source.getName().startsWith("org.springframework.")));
    }

    @Test
    void theCheckStartsWithTheExamplePluginsSwitchedOn() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = RecordCheckCommand.run(new String[] {"--tantalum.data-dir=" + data, "--tantalum.workspace=" + dir.resolve("ws"),
                "--tantalum.examples.enabled=true"}, new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(Language.EN));

        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
    }
}
