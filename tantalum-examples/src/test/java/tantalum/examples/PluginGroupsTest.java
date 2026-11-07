package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.text.Language;
import tantalum.value.text.LocalText;

/** 同一个示例插件的钩子和工作类型在插件页上是一项（core-tools C13），每项都有一句说明。 */
class PluginGroupsTest {

    @Test
    void hooksAndTheirWorkShareAPluginName() {
        assertEquals(new CitationCheckHook().plugin(), new CitationWork().plugin());
        assertEquals("toolApproval", new ToolApprovalHook(java.util.Set.of("x")).plugin());
        assertEquals(new ProviderBackoffHook(Duration.ofSeconds(1)).plugin(), new ProviderStatusWork(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1)).plugin());
        assertEquals("truncateOutput", new TruncateOutputHook(10).plugin());
    }

    @Test
    void everyExampleDescribesItself() {
        // 例子示范三种语言的写法（console-i18n I10）：英、中、日都写了
        for (LocalText description : List.of(new CitationCheckHook().description(), new DailyQuotaHook(1).description(),
                new ProviderBackoffHook(Duration.ofSeconds(1)).description(), new ReadOnlyHook(java.util.Set.of()).description(),
                new TruncateOutputHook(10).description(), new PinnedContextHook("#pin").description(),
                new ToolApprovalHook(java.util.Set.of("x")).description())) {
            for (Language language : Language.values()) {
                assertFalse(description.byLanguage().getOrDefault(language, "").isBlank(), description + " " + language);
            }
        }
    }
}
