package tantalum.examples;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 端到端测试用的一台“电脑”（local-workspace 任务 19）：在临时目录里放一个 {@code a.txt}，起一个真的连接程序进程
 * {@code java -cp … tantalum.connect.Main --allow <目录> --allow-write}。令牌只放在子进程的环境变量里，不放在参数里（L2）。
 * 同一时间只有一台；测试结束时 {@link #stop} 结束进程树、删掉目录。
 */
final class E2eComputer {

    static final String FILE = "a.txt";
    static final String CONTENT = "hello world\n";

    private static E2eComputer current;

    private final Path dir;
    private final Process process;
    private final StringBuffer output = new StringBuffer();

    private E2eComputer(Path dir, Process process) {
        this.dir = dir;
        this.process = process;
        Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException closed) {
                // 进程结束
            }
        });
    }

    static synchronized E2eComputer start(String server, String token) throws IOException {
        stop();
        Path dir = Files.createTempDirectory("tantalum-connect-e2e-");
        Files.writeString(dir.resolve(FILE), CONTENT);
        ProcessBuilder builder = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", classpath(),
                "tantalum.connect.Main", "--server", server, "--allow", dir.toString(), "--allow-write", "--name", "E2E Mac")
                .redirectErrorStream(true);
        builder.environment().put("TANTALUM_TOKEN", token);
        current = new E2eComputer(dir, builder.start());
        return current;
    }

    static synchronized E2eComputer current() {
        return current;
    }

    /** 结束连接程序（先取子孙进程再逐个结束）并删掉临时目录。没有在跑的就什么都不做。 */
    static synchronized void stop() throws IOException {
        if (current == null) {
            return;
        }
        current.process.descendants().forEach(ProcessHandle::destroyForcibly);
        current.process.destroyForcibly();
        try (Stream<Path> files = Files.walk(current.dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        current = null;
    }

    boolean running() {
        return process.isAlive();
    }

    /** 退出码；还在跑时为 null。 */
    Integer exitCode() {
        return process.isAlive() ? null : process.exitValue();
    }

    String file() throws IOException {
        return Files.readString(dir.resolve(FILE));
    }

    String output() {
        return output.toString();
    }

    /** 连接程序和它用到的几个库所在的位置。 */
    private static String classpath() {
        List<Class<?>> anchors = List.of(tantalum.connect.Main.class, tantalum.local.wire.Wire.class, tantalum.value.json.Json.class,
                tools.jackson.databind.ObjectMapper.class, tools.jackson.core.JsonParser.class,
                com.fasterxml.jackson.annotation.JsonProperty.class);
        return anchors.stream().map(E2eComputer::location).distinct().collect(Collectors.joining(File.pathSeparator));
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
