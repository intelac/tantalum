package tantalum.shell.plugin;

import java.time.Duration;
import java.util.Optional;

/**
 * 在控制台上问用户、等他选（要人决定的插件工作用，例如工具审批）。问题只显示在这个用户这个会话的对话页上。
 * 只能在 {@link WorkType#run} 里用：等待受 {@code tantalum.tool-timeout} 约束，所以 {@code wait} 要比它短；钩子不能等人。
 */
@FunctionalInterface
public interface Questions {

    /**
     * 问 {@code context} 的用户。同一个 {@code question.id()} 只显示一个问题：再问一次是接着等同一个，上一次等完之后才来的回答也算。
     * 最多等 {@code wait}：有回答返回选项的 id，问题撤下；没回答返回空，问题留在页面上，等下一次再问（隔一会儿没人再问就撤下）。
     * 线程被打断（运行被取消、工作超时、关服务）时撤下问题，抛 {@link InterruptedException}。
     */
    Optional<String> ask(ToolContext context, Question question, Duration wait) throws InterruptedException;

    /** 没有控制台时（嵌入使用、测试）：没有人回答。 */
    Questions NONE = (context, question, wait) -> Optional.empty();
}
