package tantalum.value.id;

/**
 * 两种保留的会话编号的样子。主窗口的编号以 {@link #MAIN_PREFIX} 开头；帮手会话的编号是交代它的会话的编号接上交代的编号。
 * 入口建会话时照这里定种类，人不能用这两种样子的编号建别的种类的会话；别处不看编号的样子，只读会话记着的种类。
 * 只有静态方法；value 层只放记录、接口和枚举，所以写成接口。
 */
public interface SessionIds {
    String MAIN_PREFIX = "main-";

    /** 派活开的普通会话的编号前缀（dispatch）。 */
    String WORK_PREFIX = "work-";

    /** 这个人的主会话：每人一个、永不结束，编号由人算出（dispatch 需求 1）。入口只按相等认它，不按样子。 */
    static SessionId main(Principal principal) {
        return new SessionId(MAIN_PREFIX + principal.value());
    }

    /**
     * 主会话为一次交代开的普通会话：{@code work-} 接主会话编号去掉 {@code main-}、再接交代号去掉开头的 {@code t}
     * （{@code work-alice-3-0}）。不是帮手的样子，也不是主会话的样子；留给派活，人不能用它新开会话。
     */
    static SessionId work(SessionId issuer, String assignment) {
        String owner = issuer.value().startsWith(MAIN_PREFIX) ? issuer.value().substring(MAIN_PREFIX.length()) : issuer.value();
        String number = assignment.startsWith("t") ? assignment.substring(1) : assignment;
        return new SessionId(WORK_PREFIX + owner + "-" + number);
    }

    /** 编号是不是派活会话的样子（{@code work-…-数字-数字}）。 */
    static boolean workShaped(SessionId session) {
        String value = session.value();
        if (!value.startsWith(WORK_PREFIX)) {
            return false;
        }
        String[] parts = value.split("-", -1);
        return parts.length >= 4 && digits(parts[parts.length - 1]) && digits(parts[parts.length - 2]);
    }

    /** 为一次交代开的帮手会话的编号。 */
    static SessionId helper(SessionId issuer, String assignment) {
        return new SessionId(issuer.value() + "-" + assignment);
    }

    /**
     * 编号是不是帮手会话的样子（{@code …-t数字-数字}）。这样的编号留给帮手：人不能用它开会话，否则别人可以先占住一个编号，
     * 让交代送不进去（多用户）。
     */
    static boolean helperShaped(SessionId session) {
        String value = session.value();
        int dash = value.lastIndexOf("-t");
        if (dash <= 0) {
            return false;
        }
        String[] parts = value.substring(dash + 2).split("-", -1);
        return parts.length == 2 && digits(parts[0]) && digits(parts[1]);
    }

    /** 编号是不是主窗口的样子。主窗口放出的帮手编号也以这个前缀开头，但它是帮手的样子，不算。 */
    static boolean mainShaped(SessionId session) {
        return session.value().startsWith(MAIN_PREFIX) && !helperShaped(session);
    }

    private static boolean digits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }
}
