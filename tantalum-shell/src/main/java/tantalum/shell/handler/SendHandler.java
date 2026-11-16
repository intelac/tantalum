package tantalum.shell.handler;

import tantalum.value.command.Command;
import tantalum.value.command.Send;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.Event;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * “送话”命令的处理器。它只是邮递员：把内核要送的会话来话放进收件会话的入口，和工具结果走同一个输入环。
 * 没有状态、不等待、不读任何会话的状态。输入环满时隔一小会儿再放，一直放不进就放弃：这句话还记在送的会话的信箱里
 * （没确认），服务器重启后会重送。回确认（{@code Confirm}）也由它送。
 */
public final class SendHandler implements Handler {

    /** 把一条事件放进一个会话的入口；放不进（输入环满）返回 false。由管线接到输入环上。 */
    @FunctionalInterface
    public interface Post {
        boolean post(Principal user, SessionId session, Event event);
    }

    private static final int ATTEMPTS = 200;
    private static final long BACKOFF_MILLIS = 50;

    /**
     * 只供测试：这个进程处理的第几条送话不送（从 1 数，逗号分隔），像进程死在“记了账、还没送到”之间那样
     * （{@code -Dtantalum.test.drop-send-numbers=1}：交活那句；{@code 2}：回话）。回确认不算在内。
     */
    static final String DROP_SENDS = "tantalum.test.drop-send-numbers";

    private final Post post;
    private final java.util.Set<Integer> drop = java.util.Arrays.stream(System.getProperty(DROP_SENDS, "").split(","))
            .filter(each -> !each.isBlank()).map(each -> Integer.parseInt(each.strip())).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final java.util.concurrent.atomic.AtomicInteger handled = new java.util.concurrent.atomic.AtomicInteger();

    public SendHandler(Post post) {
        this.post = post;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        if (command instanceof Send && drop.contains(handled.incrementAndGet())) {
            return null;
        }
        // 两种命令都只是把一条事件放进另一个会话的入口：送话放“会话来话”，回确认放“送到了”
        tantalum.value.id.SessionId to = command instanceof Send send ? send.to() : ((tantalum.value.command.Confirm) command).to();
        Event event = command instanceof Send send ? send.message()
                : new tantalum.value.event.MessageDelivered(((tantalum.value.command.Confirm) command).message());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (post.post(command.run().principal(), to, event)) {
                return null;
            }
            Thread.sleep(BACKOFF_MILLIS);
        }
        // 这条命令没有给发命令的运行的结果：结果是另一个会话账里的事件
        return null;
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new CommandDropped(command.id(), failure.toString());
    }
}
