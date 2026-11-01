package tantalum.value.state;

import java.util.List;
import tantalum.value.model.Message;

/**
 * 压缩后替代历史开头的那段：摘要、当前请求原文、处理过的保留段。它是压缩那一刻的完整视图，
 * {@code messages[tailFrom..]} 原样接在后面。再次压缩时对整个视图切分，所以只需要一层。
 */
public record Compaction(List<Message> replacement, int tailFrom) {

    public Compaction {
        replacement = List.copyOf(replacement);
        if (tailFrom < 0) {
            throw new IllegalArgumentException("tailFrom must not be negative");
        }
    }
}
