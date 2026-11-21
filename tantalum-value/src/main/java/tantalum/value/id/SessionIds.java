package tantalum.value.id;

/**
 * 两种保留的会话编号的样子。主窗口的编号以 {@link #MAIN_PREFIX} 开头；帮手会话的编号是交代它的会话的编号接上交代的编号。
 * 入口建会话时照这里定种类，人不能用这两种样子的编号建别的种类的会话；别处不看编号的样子，只读会话记着的种类。
 * 只有静态方法；value 层只放记录、接口和枚举，所以写成接口。
 */
public interface SessionIds {
    String MAIN_PREFIX = "main-";

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
