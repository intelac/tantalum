package tantalum.value.event;

import tantalum.value.id.RequestId;

/** 用户在网页上停任务，进放出任务的会话。{@code task} 为 null 是全部停。 */
public record TaskStopRequested(RequestId request, String task) implements Event {

    public static final String TYPE = "TaskStopRequested";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
