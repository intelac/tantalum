package tantalum.shell.net;

/** 主机名判断。 */
public final class Hosts {

    private Hosts() {}

    /** 只认字面量，不走 DNS：DNS 结果不可信，安全校验也不该发网络请求（pi 相同）。空串和 null 不算。 */
    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        return host.equals("localhost") || host.equals("[::1]") || host.equals("::1")
                || host.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }
}
