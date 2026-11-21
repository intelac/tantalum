package tantalum.shell.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.value.id.RunId;
import tantalum.value.state.SessionState;

/**
 * 对话视图只留一段连续的尾巴（live-views 需求 2）：每一步之后，把切点之前的条目从内存去掉，账里还有，往前翻页时重放账。
 * 切点 {@code k}：比最新一页的起点小（最新的一页总在内存）；没有哪一轮的条目跨在它两边；它之前没有还会变的条目。
 * 这样它之前的条目都不会再变，尾巴里算出的切点、分页和整份算出的一样。
 */
final class Tail {

    private Tail() {}

    static void trim(Transcript.Conversation conversation, SessionState session) {
        List<TranscriptItem> items = new ArrayList<>(conversation.items.values());
        int latestPage = MessagePage.of(items, null).start();
        if (latestPage <= 1) {
            return;
        }
        Set<RunId> current = new java.util.HashSet<>();
        if (session != null) {
            if (session.activeRun() != null) {
                current.add(session.activeRun());
            }
            if (session.lastRun() != null) {
                current.add(session.lastRun());
            }
        }
        int limit = latestPage - 1;
        for (int i = 0; i < limit; i++) {
            if (changing(items.get(i), current)) {
                limit = i;
                break;
            }
        }
        boolean[] cuttable = MessagePage.cuttable(items, items.size());
        int k = limit;
        while (k > 0 && !cuttable[k]) {
            k--;
        }
        if (k <= 0) {
            return;
        }
        for (TranscriptItem gone : items.subList(0, k)) {
            conversation.items.remove(gone.id());
        }
        conversation.stepByCall.values().removeIf(id -> !conversation.items.containsKey(id));
        conversation.offset += k;
    }

    /** 还会被哪一步改的条目：正在跑或最近结束的那一轮的、没结束的工具步骤、没回答的问题、排队的提问、没结果的交代卡片。 */
    static boolean changing(TranscriptItem item, Set<RunId> current) {
        if (item.run() != null && current.contains(item.run())) {
            return true;
        }
        return switch (item) {
            case TranscriptItem.ToolStep step -> !step.isFinished();
            case TranscriptItem.Question question -> question.status() == TranscriptItem.QuestionStatus.OPEN;
            case TranscriptItem.UserMessage message -> message.queued();
            case TranscriptItem.Task task -> task.outcome() == null;
            default -> false;
        };
    }
}
