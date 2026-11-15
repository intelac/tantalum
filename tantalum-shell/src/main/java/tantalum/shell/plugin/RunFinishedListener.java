package tantalum.shell.plugin;

import java.time.Instant;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 运行结束后（memory-plugin 需求 1.1）：一轮完成并落盘以后，平台在后台线程上通知。慢不影响对话，抛出只记日志。
 * 只通知插件开着的用户；启动时补索引、重建不通知。
 */
public interface RunFinishedListener {

    /** 所属插件（插件页上的那一行）。 */
    String plugin();

    void runFinished(FinishedRun run);

    /**
     * 结束的一轮。{@code endReason} 是 {@code COMPLETED}、{@code FAILED}、{@code CANCELLED}、{@code STOPPED} 之一；
     * {@code manual} 为真是手动压缩；{@code excerpt} 是这一轮的文字摘录（用户的话、工具和截短遮盖后的结果、最后的回答，
     * 插件附加的内容已去掉）。
     */
    record FinishedRun(Principal user, SessionId session, RunId run, Integer turn, String endReason, boolean manual, Instant start,
                       Instant end, String title, String excerpt) {}
}
