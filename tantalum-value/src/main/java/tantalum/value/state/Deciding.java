package tantalum.value.state;

/** 刚拿到事件，正在决定下一步。 */
public record Deciding() implements RunPhase {

    @Override
    public String name() {
        return "Deciding";
    }
}
