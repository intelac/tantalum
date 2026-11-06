package tantalum.value.id;

import java.util.Locale;

/**
 * Windows 保留的设备名（windows-server N5），名单照微软《Naming Files, Paths, and Namespaces》。这些名字带不带扩展名都指向设备：
 * 写进 {@code nul.jsonl} 的数据悄悄丢失。用户名、记忆名拒绝，会话文件名编码避开；所有平台同一条规则，数据目录换平台时文件名不变。
 * 带上标的 {@code COM¹} 这类也是保留名，但三种名字都只许 ASCII，不会出现，不列。pi、DSH 都没有专门处理，自定。
 */
public enum ReservedName {
    CON, PRN, AUX, NUL,
    COM0, COM1, COM2, COM3, COM4, COM5, COM6, COM7, COM8, COM9,
    LPT0, LPT1, LPT2, LPT3, LPT4, LPT5, LPT6, LPT7, LPT8, LPT9;

    /** 第一个句点之前的部分（不分大小写）是保留名：{@code nul}、{@code NUL}、{@code nul.jsonl}、{@code con.md} 都算。 */
    public static boolean matches(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.indexOf('.');
        String stem = (dot < 0 ? fileName : fileName.substring(0, dot)).toUpperCase(Locale.ROOT);
        for (ReservedName name : values()) {
            if (name.name().equals(stem)) {
                return true;
            }
        }
        return false;
    }
}
