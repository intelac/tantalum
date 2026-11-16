package tantalum.value.event;

/**
 * 服务器重启后发给“有话送出去还没确认”的会话：把这些话照原来的编号再送一次。上一个进程可能死在“送话记了账、还没送进对方会话”
 * 之间；对方已经收到的按编号去重，只回确认。
 */
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
