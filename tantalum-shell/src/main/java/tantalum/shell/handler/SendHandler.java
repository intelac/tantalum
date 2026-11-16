package tantalum.shell.handler;

import tantalum.value.command.Command;
import tantalum.value.command.Send;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.Event;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * “送话”命令的处理器。它只是邮递员：把内核要送的会话来话放进收件会话的入口，和工具结果走同一个输入环。
 * 没有状态、不等待、不读任何会话的状态。输入环满时隔一小会儿再放，几次都放不进就放弃（这句话丢了，等回话的一方会等到期限）。
 */
public final class SendHandler implements Handler {

    /** 把一条事件放进一个会话的入口；放不进（输入环满）返回 false。由管线接到输入环上。 */
    @FunctionalInterface
    public interface Post {
        boolean post(Principal user, SessionId session, Event event);
    }

    private static final int ATTEMPTS = 20;
    private static final long BACKOFF_MILLIS = 50;

    private final Post post;

    public SendHandler(Post post) {
        this.post = post;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        Send send = (Send) command;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (post.post(send.run().principal(), send.to(), send.message())) {
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
