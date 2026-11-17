package tantalum.local.wire;

import java.util.List;
import java.util.Map;
import static tantalum.local.wire.Hello.*;
import tantalum.local.wire.Hello.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class HelloFixture {

    private HelloFixture() {}

    public static Hello of(int protocol, String connector, Device device, List<Root> roots, boolean write, Shell shell, boolean network, boolean git) {
        return new Hello(protocol, connector, device, roots, write, shell, network, git, null);
    }

    public static Hello.Shell shell(boolean available, String reason) {
        return new Hello.Shell(available, reason, null, null, null, null);
    }

    public static Hello.Shell shell(boolean available, String reason, String code, Map<String, String> params) {
        return new Hello.Shell(available, reason, code, params, null, null);
    }
}
