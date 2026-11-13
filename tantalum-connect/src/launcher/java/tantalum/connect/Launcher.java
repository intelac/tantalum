package tantalum.connect;

/**
 * jar 的入口（connector-jar 需求 1.3）：先看 Java 版本，够 25 才进 {@link Main}。这个类单独按 Java 8 编译：连接程序的别的类是
 * Java 25 的字节码，旧的 Java 直接运行会报 {@code UnsupportedClassVersionError}，用户看不懂。这里不用 Java 8 以后的 API。
 */
public final class Launcher {

    static final int REQUIRED = 25;

    private Launcher() {}

    public static void main(String[] args) throws Throwable {
        String spec = System.getProperty("java.specification.version", "0");
        int feature = feature(spec);
        if (feature < REQUIRED) {
            System.err.println("tantalum-connect needs Java " + REQUIRED + " or newer; this is Java " + spec + " ("
                    + System.getProperty("java.home") + ").");
            System.err.println("tantalum-connect 需要 Java " + REQUIRED + " 或更新的版本；现在用的是 Java " + spec + "。");
            System.err.println("Download / 下载: https://adoptium.net/temurin/releases/?version=" + REQUIRED);
            System.exit(1);
        }
        try {
            Class.forName("tantalum.connect.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /** {@code 1.8} 是 8，{@code 25} 是 25；认不出来当 0。 */
    static int feature(String spec) {
        String number = spec.startsWith("1.") ? spec.substring(2) : spec;
        int dot = number.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? number : number.substring(0, dot));
        } catch (NumberFormatException odd) {
            return 0;
        }
    }
}
