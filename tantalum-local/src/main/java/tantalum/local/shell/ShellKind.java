package tantalum.local.shell;

import java.util.Locale;

/**
 * 按系统只用一种 shell：Windows 上 PowerShell，其它 bash。照 DSH 按平台只装一个命令工具
 * （{@code packages/preset/agent-presets/presets/standard/agent.cordis.yml:45-51}）。
 */
public enum ShellKind {
    BASH("bash"),
    POWERSHELL("powershell");

    private final String wire;

    ShellKind(String wire) {
        this.wire = wire;
    }

    /** 协议和工具名里的写法：{@code bash}、{@code powershell}。 */
    public String wire() {
        return wire;
    }

    public static ShellKind current() {
        return of(System.getProperty("os.name", ""));
    }

    static ShellKind of(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows") ? POWERSHELL : BASH;
    }

    /** 协议里的写法转回来；不认得（老的连接程序不送）时为 null。 */
    public static ShellKind fromWire(String wire) {
        for (ShellKind kind : values()) {
            if (kind.wire.equals(wire)) {
                return kind;
            }
        }
        return null;
    }
}
