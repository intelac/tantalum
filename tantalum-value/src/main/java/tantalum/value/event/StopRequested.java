package tantalum.value.event;

import tantalum.value.id.RequestId;

/** 用户在网页上叫停交代出去的事，进交代它的会话。{@code assignment} 为 null 是全部停。 */
public record StopRequested(RequestId request, String assignment) implements Event {

    public static final String TYPE = "StopRequested";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
