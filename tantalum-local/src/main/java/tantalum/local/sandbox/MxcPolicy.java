package tantalum.local.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tantalum.local.fs.AllowedDirs;
import tools.jackson.databind.json.JsonMapper;

/**
 * 交给 {@code mxc-exec-mac} 的 JSON 规则（local-workspace 设计“Mxc.run 第 1 步”，L13、L14）。MXC 从“全部拒绝”开始，只放开系统目录
 * （{@code docs/backends/seatbelt/seatbelt-backend.md} “Always-on baseline”），这里再加：
 * <ul>
 *   <li>读写：有 {@code --allow-write} 时的允许目录，以及这次调用的临时目录（git 这类工具要可写的 {@code TMPDIR}，实测）；</li>
 *   <li>只读：没有 {@code --allow-write} 时的允许目录、{@code /opt/homebrew}、{@code /usr/local}（常见工具链，实测）、
 *       {@code --allow-read} 的目录、命令的家目录；</li>
 *   <li>禁止：{@code ~/.ssh}、{@code ~/Library/Keychains}，就算允许目录把它们包在里面也读不到（MXC 的禁止规则最后生效）；</li>
 *   <li>网络：出站整体开或关（{@code --no-network}），MXC 在 macOS 上不能按网址放行；连本机一律拒绝，命令碰不到 Tantalum 自己的接口；</li>
 *   <li>环境变量：MXC 本来不继承宿主的环境变量，这里只给 {@code PATH}、{@code HOME}（连接程序自己的目录：设成真实的家目录时
 *       git 读 {@code ~/.gitconfig} 被拒会报错，实测）、{@code TMPDIR}、{@code LANG}，以及 {@code --env} 放行的变量。
 *       名字里有 {@code KEY}、{@code TOKEN}、{@code SECRET}、{@code PASSWORD} 的一律不给（照 DS 原型 {@code docs/006-deployment.md:218}）。</li>
 * </ul>
 * DSH 的 Seatbelt 规则允许读任何地方（{@code sandbox/sandbox-local/src/profiles.ts:53-60}），这里更严，因为要防的是服务器被攻破。
 *
 * @param dirs 允许的目录（真实路径），第一个是主目录
 * @param readDirs {@code --allow-read} 的目录
 * @param env {@code --env} 放行的变量和它们在这台电脑上的值；有密钥样子的名字已去掉
 * @param userHome 这台电脑真实的家目录，用来算禁止的目录
 */
public record MxcPolicy(List<Path> dirs, boolean write, List<Path> readDirs, boolean network, Map<String, String> env,
                        Path userHome) {

    static final String PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
    static final List<String> TOOLCHAINS = List.of("/opt/homebrew", "/usr/local");
    static final List<String> DENIED = List.of(".ssh", "Library/Keychains");
    /** 只由连接程序决定、{@code --env} 不能改的变量。 */
    private static final List<String> FIXED = List.of("HOME", "TMPDIR");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public MxcPolicy {
        dirs = List.copyOf(dirs);
        readDirs = List.copyOf(readDirs);
        Objects.requireNonNull(userHome, "userHome");
        Map<String, String> kept = new LinkedHashMap<>();
        env.forEach((name, value) -> {
            if (!secret(name) && !FIXED.contains(name) && value != null) {
                kept.put(name, value);
            }
        });
        env = Map.copyOf(kept);
    }

    /** 按启动参数算规则：{@code envNames} 是 {@code --env} 给的名字，值从 {@code hostEnv}（连接程序自己的环境变量）里取。 */
    public static MxcPolicy of(AllowedDirs dirs, boolean write, List<Path> readDirs, boolean network, List<String> envNames,
                               Map<String, String> hostEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : envNames) {
            String value = hostEnv.get(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        return new MxcPolicy(dirs.all(), write, readDirs, network, env, Path.of(System.getProperty("user.home")));
    }

    /** 名字像密钥的环境变量（L14）：含 {@code KEY}、{@code TOKEN}、{@code SECRET}、{@code PASSWORD}，不分大小写；{@code TANTALUM_TOKEN} 也在内。 */
    public static boolean secret(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD");
    }

    /**
     * 一次调用的规则：用 bash 跑 {@code command}，工作目录 {@code workdir}，临时目录 {@code temp}（可写，调用结束后删掉），
     * 家目录 {@code home}（连接程序的 {@code sandbox-home}，只读），到 {@code timeout} 由 MXC 结束整个进程树。
     */
    public String config(String command, Path workdir, Path temp, Path home, Duration timeout) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("commandLine", "/bin/bash -c " + quote(command));
        process.put("cwd", workdir.toString());
        process.put("timeout", timeout.toMillis());
        process.put("env", environment(temp, home));

        List<String> readwrite = new ArrayList<>();
        List<String> readonly = new ArrayList<>();
        for (Path dir : dirs) {
            (write ? readwrite : readonly).add(dir.toString());
        }
        readwrite.add(temp.toString());
        for (String toolchain : TOOLCHAINS) {
            if (Files.isDirectory(Path.of(toolchain))) {
                readonly.add(toolchain);
            }
        }
        readDirs.forEach(dir -> readonly.add(dir.toString()));
        readonly.add(home.toString());
        // 禁止的目录写两种：字面路径和真实路径。~/.ssh 是符号链接时 MXC 按真实路径判断，只写字面的拦不住（review-fixes 需求 11.10）
        List<String> denied = new ArrayList<>();
        for (String name : DENIED) {
            Path literal = userHome.resolve(name);
            denied.add(literal.toString());
            try {
                Path real = literal.toRealPath();
                if (!real.equals(literal)) {
                    denied.add(real.toString());
                }
            } catch (IOException | RuntimeException missing) {
                // 不存在或读不到：字面路径就够了
            }
        }

        Map<String, Object> filesystem = new LinkedHashMap<>();
        filesystem.put("readwritePaths", readwrite);
        filesystem.put("readonlyPaths", readonly);
        filesystem.put("deniedPaths", denied);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "1.0.0");
        config.put("containment", "seatbelt");
        config.put("process", process);
        config.put("filesystem", filesystem);
        config.put("network", Map.of(
                "egress", Map.of("default", network ? "allow" : "deny"),
                "ingress", Map.of("default", "deny", "hostLoopback", "deny")));
        return JSON.writeValueAsString(config);
    }

    /** {@code process.env}：MXC 要 {@code "名字=值"} 的数组，给了就不再加它自己的默认值。 */
    private List<String> environment(Path temp, Path home) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PATH", PATH);
        values.put("HOME", home.toString());
        values.put("TMPDIR", temp.toString());
        values.put("LANG", "en_US.UTF-8");
        values.putAll(env);
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    }

    /** 包成 bash 的单引号字符串：里面的单引号写成 {@code '\''}。 */
    static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
