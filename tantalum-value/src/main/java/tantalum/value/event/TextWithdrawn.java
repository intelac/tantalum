package tantalum.value.event;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 把会话里的几段文字撤下：模型以后不再看到，记录页、搜索也不再给出。原文还在账里（这条事件自己就带着），所以不是删除。
 * <p>{@code swaps} 里每一项是“原文、换成什么”。内核只按字面办：会话的消息、压缩后的替换段、排队的提问、冻结的上下文段里，
 * 哪里逐字出现了原文就换掉，没出现的不动。哪些文字该撤下不是内核定的：是用户在页面上勾的，或者模型在一次工具调用里选的，
 * {@code by} 记着是谁（{@code session}、{@code run}、{@code call} 只在模型选的时候有，指那次工具调用）。
 * <p>重复送来没有害处：换过的地方已经不再有原文。带签名的思考块不动（改了模型提供方会拒绝）。
 */
public record TextWithdrawn(RequestId request, List<Swap> swaps, By by) implements Event {

    public static final String TYPE = "TextWithdrawn";

    /** 谁定的。 */
    public enum Actor { USER, AGENT }

    /** 原文和换成的文字。 */
    public record Swap(String original, String replacement) {

        public Swap {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(replacement, "replacement");
        }

        /** 能不能照办：原文和替换都不是空白，替换里不再含原文（否则换了还在，重复送来会越换越多）。 */
        public boolean usable() {
            return !original.isBlank() && !replacement.isBlank() && !replacement.contains(original);
        }
    }

    public record By(Actor actor, SessionId session, RunId run, String call) {

        public By {
            Objects.requireNonNull(actor, "actor");
        }

        public static By user() {
            return new By(Actor.USER, null, null, null);
        }
    }

    public TextWithdrawn {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(by, "by");
        swaps = List.copyOf(swaps);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
