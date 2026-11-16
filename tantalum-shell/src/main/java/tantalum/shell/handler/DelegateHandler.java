package tantalum.shell.handler;

import tantalum.value.command.CancelDelegate;
import tantalum.value.command.Command;
import tantalum.value.command.Delegate;
import tantalum.value.command.Return;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.DelegateCancelled;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Event;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 委派的三条命令（kernel-delegation）的处理器。它只是邮递员：把内核要送的事件放进另一个会话的入口，和工具结果走同一个输入环。
 * 没有状态、不等待、不读任何会话的状态；谁在等谁、做完没有都在两边的账里，由事件推动。
 * <ul>
 * <li>{@link Delegate}：把交活送进子会话。送不进去时往父会话送一条“没开成”的交回。</li>
 * <li>{@link Return}：把交回送进父会话。</li>
 * <li>{@link CancelDelegate}：告诉每个子会话父会话不等了。</li>
 * </ul>
 * 输入环满时隔一小会儿再放，几次都放不进就放弃：交活和交回丢了，由期限和重启后的重发（{@code Recovered}）补上。
 */
public final class DelegateHandler implements Handler {

    /** 把一条事件放进一个会话的入口；放不进（输入环满）返回 false。由管线接到输入环上。 */
    @FunctionalInterface
    public interface Post {
        boolean post(Principal user, SessionId session, Event event);
    }

    private static final int ATTEMPTS = 20;
    private static final long BACKOFF_MILLIS = 50;

    private final Post post;

    public DelegateHandler(Post post) {
        this.post = post;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        Principal user = command.run().principal();
        switch (command) {
            case Delegate handoff -> {
                if (!post(user, handoff.child(), handoff.handoff())) {
                    post(user, handoff.run().session(), new DelegateReturned(handoff.handoff().delegation(), handoff.child(), null,
                            DelegateReturned.Kind.REFUSED, "the server is too busy to start it now", null));
                }
            }
            case Return back -> post(user, back.parent(), back.outcome());
            case CancelDelegate cancel -> {
                for (CancelDelegate.Target target : cancel.targets()) {
                    post(user, target.child(), new DelegateCancelled(target.delegation()));
                }
            }
            default -> throw new IllegalArgumentException("not a delegation command: " + command.getClass().getSimpleName());
        }
        // 这三条命令没有给发命令的运行的结果：结果是别的会话账里的事件
        return null;
    }

    private boolean post(Principal user, SessionId session, Event event) throws InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (post.post(user, session, event)) {
                return true;
            }
            Thread.sleep(BACKOFF_MILLIS);
        }
        return false;
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new CommandDropped(command.id(), failure.toString());
    }
}
