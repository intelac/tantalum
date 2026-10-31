package tantalum.value.event;

import tantalum.value.id.RequestId;

/** 用户撤销运行。 */
public record Cancel(RequestId request) implements Event {

    public static final String TYPE = "Cancel";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
