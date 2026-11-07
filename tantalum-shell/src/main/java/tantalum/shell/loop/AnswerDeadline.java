package tantalum.shell.loop;

import java.time.Instant;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 索引里记的一个等用户回答且有期限的运行（waiting-state 需求 3.4）：启动时 {@link DeadlineTimers} 据此重起定时器。 */
public record AnswerDeadline(SessionId session, Principal principal, RunId run, Instant deadline, CommandId timer) {}
