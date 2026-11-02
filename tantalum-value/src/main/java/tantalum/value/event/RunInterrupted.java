package tantalum.value.event;

/** 服务重启时运行还没结束。启动时发布，结果已无法得知，所以结束运行而不是重新执行。 */
public record RunInterrupted() implements Event {

    public static final String TYPE = "RunInterrupted";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
