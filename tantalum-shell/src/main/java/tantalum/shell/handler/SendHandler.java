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

    /**
     * 这个会话编号是不是这个用户的。会话编号全服务器唯一，谁先用归谁：还没人用过的当场记到这个用户名下（返回 {@link #MINE}），
     * 已经是别人的返回 {@link #NOT_MINE}，一时查不了返回 {@link #UNKNOWN}。只查归属这张表，不读会话的状态。
     */
    @FunctionalInterface
    public interface Owner {
        int of(Principal user, SessionId session);
    }

    public static final int MINE = 200;
    public static final int NOT_MINE = 404;
    public static final int UNKNOWN = 503;

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

    private final Owner owner;

    public SendHandler(Post post, Owner owner) {
        this.post = post;
        this.owner = owner;
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
        // 模型写的会话编号可能是别的用户的会话：一个字也不送进去
        int whose = owner.of(command.run().principal(), to);
        if (whose == NOT_MINE) {
            if (command instanceof Send send) {
                refuse(send);
            }
            return null;
        }
        if (whose != MINE) {
            // 一时查不了归属：不送。话还记在送的会话的信箱里，重启后重送
            return null;
        }
        deliver(command.run().principal(), to, event);
        // 这条命令没有给发命令的运行的结果：结果是另一个会话账里的事件
        return null;
    }

    private boolean deliver(Principal user, SessionId to, Event event) throws InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (post.post(user, to, event)) {
                return true;
            }
            Thread.sleep(BACKOFF_MILLIS);
        }
        return false;
    }

    /**
     * 送不到（那个编号不是这个用户的会话）：告诉送的会话“这句送到头了”，不用再留着重送；是交活的，再替对方回一句“没做成”，
     * 这样等结果的那次调用当场得到答复，每件交出去的活仍然有一句回话。
     */
    private void refuse(Send send) throws InterruptedException {
        Principal user = send.run().principal();
        SessionId back = send.run().session();
        tantalum.value.event.SessionMessage message = send.message();
        deliver(user, back, new tantalum.value.event.MessageDelivered(message.id()));
        if (message.isRequest()) {
            tantalum.value.id.RunRef nowhere = new tantalum.value.id.RunRef(user, send.to(), tantalum.value.id.RunId.of(send.to(), 0));
            deliver(user, back, new tantalum.value.event.SessionMessage(
                    new tantalum.value.id.CommandId(message.id().value() + "-undelivered"), nowhere,
                    "it could not be delivered: you have no conversation with the id \"" + send.to().value() + "\".",
                    message.id(), true, message.hops(), null, null, null));
        }
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new CommandDropped(command.id(), failure.toString());
    }
}
