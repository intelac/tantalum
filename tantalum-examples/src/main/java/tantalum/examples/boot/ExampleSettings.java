package tantalum.examples.boot;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 示例插件的配置 {@code tantalum.examples.*}。{@code enabled} 是总开关，默认关：示例 jar 在类路径上（测试、{@code loader.path}）也不注册，
 * 部署写 {@code --tantalum.examples.enabled=true} 才注册。每个插件各有 {@code enabled}（默认开）和自己的设置。
 * 注册了的插件在插件页上列出；策略插件（只读、工具审批、每日额度）对每个用户默认关，由用户自己打开。
 */
@ConfigurationProperties("tantalum.examples")
public record ExampleSettings(boolean enabled, @DefaultValue ReadOnly readOnly, @DefaultValue ToolApproval toolApproval,
                              @DefaultValue DailyQuota dailyQuota, @DefaultValue PinnedContext pinnedContext,
                              @DefaultValue ProviderBackoff providerBackoff, @DefaultValue CitationCheck citationCheck,
                              @DefaultValue TruncateOutput truncateOutput) {

    /** 只读策略：只放行 {@code tools} 里的工具。默认是读文件、Git 查询、附件和记忆。 */
    public record ReadOnly(@DefaultValue("true") boolean enabled,
                           @DefaultValue({"read_attachment", "read_file", "list_dir", "find_files", "grep", "git_log", "git_show",
                                   "git_diff", "git_blame", "git_branches", "memory_read", "memory_write", "memory_delete"})
                           List<String> tools) {}

    /** 工具审批：调用 {@code tools} 前在对话页上问，等多久都行。以前的 {@code answer-wait}、{@code max-polls} 已删除。 */
    public record ToolApproval(@DefaultValue("true") boolean enabled,
                               @DefaultValue({"write_file", "edit_file", "bash"}) List<String> tools) {}

    /** 每日额度：每个用户每天（UTC）的 token 上限，输入（含缓存）加输出。 */
    public record DailyQuota(@DefaultValue("true") boolean enabled, @DefaultValue("2000000") long tokens) {}

    /** 钉住的上下文：用户消息里带 {@code marker} 的，不被自动或手动压缩总结掉。 */
    public record PinnedContext(@DefaultValue("true") boolean enabled, @DefaultValue("#pin") String marker) {}

    /**
     * 提供方退避：限流要等的比 {@code maxWait} 久就结束运行；服务端错误、连接错误时每隔 {@code poll} 查一次 {@code statusUrl}，
     * 2xx 就重试。{@code statusUrl} 不配就不注册这个插件（没有地址可查）。
     */
    public record ProviderBackoff(@DefaultValue("true") boolean enabled, String statusUrl, @DefaultValue("60s") Duration maxWait,
                                  @DefaultValue("10s") Duration poll) {}

    /** 引用核对：回答里的 文件:行号 必须是这个会话里读到过的那一行。 */
    public record CitationCheck(@DefaultValue("true") boolean enabled) {}

    /** 输出截断：工具结果超过 {@code maxChars} 个字符时截断。 */
    public record TruncateOutput(@DefaultValue("true") boolean enabled, @DefaultValue("100000") int maxChars) {}
}
