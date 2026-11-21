package tantalum.value.event;

/** 启动时给信箱不空的会话（delivery 需求 1.5）：每个对方的第一条照原编号再发，重起确认定时器。 */
public record Redeliver() implements Event {

    public static final String TYPE = "Redeliver";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
