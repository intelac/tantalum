package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * compaction-threshold 需求 4.4：仓库里的真实文字（Java、中文、英文、带 id 的 JSON）用默认系数估算，不小于模型实测的
 * token 数。实测值在 {@code estimate/counts.txt}，样本或系数改了要重新测（scratchpad 的 measure.py 做法写在文件头）。
 */
class EstimateSamplesTest {

    /** 和 application.properties 的默认值相同。 */
    private static final double CLAUDE = 1.5;
    private static final double QWEN = 1.75;

    record Sample(String name, long claude, long qwen) {}

    static List<Sample> samples() throws IOException {
        return read("counts.txt").lines().filter(line -> !line.startsWith("#") && !line.isBlank()).map(line -> {
            String[] parts = line.trim().split("\\s+");
            return new Sample(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        }).toList();
    }

    private static String read(String name) throws IOException {
        try (InputStream in = EstimateSamplesTest.class.getResourceAsStream("/estimate/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void theDefaultCoefficientsNeverUnderestimate(Sample sample) throws IOException {
        String text = read(sample.name() + ".txt");
        long claude = new TokenEstimate(CLAUDE).ofText(text);
        long qwen = new TokenEstimate(QWEN).ofText(text);
        assertTrue(claude >= sample.claude(), sample.name() + ": Claude estimate " + claude + " < measured " + sample.claude());
        assertTrue(qwen >= sample.qwen(), sample.name() + ": qwen estimate " + qwen + " < measured " + sample.qwen());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void theOldRuleUnderestimatedClaude(Sample sample) throws IOException {
        // 改系数的原因：按字节数除以 3，Claude 的四类样本都少算
        assertTrue(TokenEstimate.LEGACY.ofText(read(sample.name() + ".txt")) < sample.claude(), sample.name());
    }

    @org.junit.jupiter.api.Test
    void allFourKindsAreMeasured() throws IOException {
        assertEquals(List.of("java", "chinese", "english", "json"), samples().stream().map(Sample::name).toList());
    }
}
