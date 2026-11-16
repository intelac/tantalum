package tantalum.value.event;

/**
 * 服务重启后，给正在等委派的运行（kernel-delegation）。和 {@link RunInterrupted} 是一对：那个给手上有进行中命令的运行（结束它），
 * 这个给没有进行中命令、在等别的会话交回的运行（不结束它，让它把交活再发一次，两边按委派号去重）。
 */
public record Recovered() implements Event {

    public static final String TYPE = "Recovered";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
