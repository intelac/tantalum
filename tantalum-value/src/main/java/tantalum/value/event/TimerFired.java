package tantalum.value.event;

import tantalum.value.id.CommandId;

/** 定时器到时。 */
public record TimerFired(CommandId command) implements ResultEvent {

    public static final String TYPE = "TimerFired";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
