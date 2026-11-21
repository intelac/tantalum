package tantalum.value.event;

/**
 * 一条输入、一轮运行追到头是谁引起的。只有两档，{@code OUTSIDE} 比 {@code USER} 低：一轮吃进去的输入里只要有一条是外面引起的，
 * 这一轮就是外面引起的，之后不会再升回去。你给过的放行只在 {@code USER} 的那一轮里认。
 */
public enum Root {
    USER,
    OUTSIDE;

    /** 两个里更低的那个。 */
    public static Root lower(Root one, Root other) {
        return one == OUTSIDE || other == OUTSIDE ? OUTSIDE : USER;
    }
}
