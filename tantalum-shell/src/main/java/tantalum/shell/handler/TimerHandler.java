package tantalum.shell.handler;

import tantalum.value.command.Command;
import tantalum.value.command.StartTimer;
import tantalum.value.event.Event;
import tantalum.value.event.TimerFired;

/** 等到时间后发 {@code TimerFired}。被中断时运行已结束，结果会被内核当作过期事件。 */
public final class TimerHandler implements Handler {

    @Override
    public Event handle(Command command) throws InterruptedException {
        StartTimer timer = (StartTimer) command;
        Thread.sleep(timer.delay());
        return new TimerFired(timer.id());
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new TimerFired(command.id());
    }
}
