package tantalum.shell.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联网的唯一出口（web-tools 设计“出口”）：进程内的 HTTP/CONNECT 代理，只听 {@code 127.0.0.1} 的随机端口。
 * {@code web_fetch} 和浏览器的每一个连接都经过这里：解析一次域名，每个地址都过 {@link AddressRules}，然后直接连到检查过的那批地址，
 * 检查和连接之间没有第二次 DNS 解析，DNS rebinding 换不了地址。
 * <p>明文 http 一条连接只转一个请求，转完就关（设计时实测的漏洞：代理只看了第一个请求头，同一条连接上的第二个请求直接进了第一个目标）。
 * 回复里也写上 {@code Connection: close}，客户端不会再复用这条连接。
 * <p>日志只记主机、端口和原因类别，不记路径（路径里可能有令牌）、正文和 TLS 里的内容（出口本来也看不到）。
 */
public final class Egress implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Egress.class);

    /** 出口拦下时回 403，带这个头；值是 {@link AddressRules.Reason#header}。网站自己发的同名头在转发时去掉。 */
    public static final String BLOCKED_HEADER = "X-Tantalum-Blocked";
    /**
     * 出口自己连不上时（解析不了、连不上、上游代理拒绝、连接太多）回 502/503，带这个头，值是原因类别；明文请求的调用方据此分清
     * 是出口的错误还是网站自己的 502。网站自己发的同名头同样去掉。
     */
    public static final String ERROR_HEADER = "X-Tantalum-Egress";

    static final int MAX_CONNECTIONS = 512;
    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_RESPONSE_HEAD = 64 * 1024;
    private static final int HEAD_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int POLL_MS = 30_000;
    private static final long IDLE_NANOS = Duration.ofMinutes(5).toNanos();
    /** 明文请求从发出到转完回复最长 10 分钟：一个字节一个字节慢慢发的网站也只能占一个名额这么久。 */
    private static final long PLAIN_LIFETIME_NANOS = Duration.ofMinutes(10).toNanos();
    private static final int ACCEPT_BACKOFF_MS = 200;
    /** 一个名字最多试 4 个地址，每个 10 秒：返回几十个连不上的地址的 DNS 拖不住线程。 */
    private static final int MAX_ADDRESSES_TRIED = 4;
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("(?i)0x[0-9a-f]*|[0-9]+");
    private static final Pattern CANONICAL_IPV4 = Pattern.compile("((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}"
            + "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._\\-]+|\\[[0-9A-Fa-f:.]+]");
    /** 逐跳的请求头，转发时去掉；{@code Expect} 去掉后网站不发 100，{@code Upgrade} 去掉后不会升级成别的协议。 */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection", "proxy-authorization",
            "upgrade", "http2-settings", "expect", "te", "trailer");

    private final AddressRules rules;
    private final UpstreamProxy upstream;
    private final ServerSocket server;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final HttpClient client;
    private volatile boolean closed;

    private Egress(AddressRules rules, UpstreamProxy upstream, ServerSocket server) {
        this.rules = rules;
        this.upstream = upstream;
        this.server = server;
        this.client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 只听 {@code 127.0.0.1}；自己的端口报给 {@code rules}，经出口再连出口也被拦。 */
    public static Egress start(AddressRules rules, UpstreamProxy upstream) throws IOException {
        ServerSocket server = new ServerSocket(0, MAX_CONNECTIONS, InetAddress.getLoopbackAddress());
        rules.egressPort(server.getLocalPort());
        Egress egress = new Egress(rules, upstream, server);
        Thread.ofVirtual().name("egress-accept").start(egress::acceptLoop);
        return egress;
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 给 {@code web_fetch}：经出口，不自动跟跳转，连接超时 10 秒。联网的代码不自己建 {@code HttpClient}（ArchitectureTest 规则 3）。 */
    public HttpClient client() {
        return client;
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关不掉也没有别的办法
        }
        open.forEach(Egress::quietly);
        client.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException failure) {
                if (closed) {
                    return;
                }
                // 比如文件句柄用完（EMFILE）：等一下再接，不能一次出错就再也不接
                LOG.warn("web egress could not accept a connection: {}", failure.getClass().getSimpleName());
                try {
                    Thread.sleep(ACCEPT_BACKOFF_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                continue;
            }
            if (!slots.tryAcquire()) {
                // 同时最多 512 条，再多回 503，不让一个失控的页面占满线程和文件句柄。
                try (socket) {
                    respond(socket.getOutputStream(), 503, "Service Unavailable", ERROR_HEADER + ": busy", "Too many connections through this server.");
                } catch (IOException ignored) {
                    // 对方已经走了
                }
                continue;
            }
            Thread.ofVirtual().name("egress").start(() -> {
                try {
                    handle(socket);
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void handle(Socket socket) {
        open.add(socket);
        try (socket) {
            socket.setSoTimeout(HEAD_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String head;
            try {
                head = readHead(in, MAX_HEAD);
            } catch (HeadTooLarge tooLarge) {
                respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "The request head is larger than 16 KB.");
                return;
            }
            if (head == null) {
                return;
            }
            List<String> lines = head.lines().toList();
            String[] start = lines.isEmpty() ? new String[0] : lines.getFirst().split(" ");
            if (start.length != 3) {
                respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "Not an HTTP request.");
                return;
            }
            if (start[0].equals("CONNECT")) {
                tunnel(socket, in, out, start[1]);
            } else if (start[1].regionMatches(true, 0, "http://", 0, 7)) {
                plain(socket, in, out, start[0], start[1], lines.subList(1, lines.size()));
            } else {
                respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "Only CONNECT and absolute http:// requests are accepted.");
            }
        } catch (IOException ended) {
            // 对方断开、超时：这条连接到此为止
        } catch (RuntimeException unexpected) {
            LOG.debug("web egress dropped a malformed connection: {}", unexpected.getClass().getSimpleName());
        } finally {
            open.remove(socket);
        }
    }

    /** 目标：主机（IPv6 带方括号）、端口。 */
    private record Target(String host, int port) {

        String bare() {
            return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        }

        String authority() {
            return host + ":" + port;
        }
    }

    private void tunnel(Socket socket, InputStream in, OutputStream out, String authority) throws IOException {
        int colon = authority.lastIndexOf(':');
        Optional<Target> parsed = colon < 0 ? Optional.empty() : target(authority.substring(0, colon), authority.substring(colon + 1));
        if (parsed.isEmpty()) {
            respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "CONNECT needs host:port.");
            return;
        }
        Target target = parsed.get();
        UpstreamProxy.Route route = upstream.route(target.host(), target.port(), true);
        Optional<Socket> remote = connect(target, route, out);
        if (remote.isEmpty()) {
            return;
        }
        Socket far = remote.get();
        try {
            InputStream farIn = new BufferedInputStream(far.getInputStream());
            if (route instanceof UpstreamProxy.Via via) {
                StringBuilder request = new StringBuilder("CONNECT ").append(target.authority()).append(" HTTP/1.1\r\n")
                        .append("Host: ").append(target.authority()).append("\r\n");
                if (via.authorization() != null) {
                    request.append("Proxy-Authorization: ").append(via.authorization()).append("\r\n");
                }
                far.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
                far.setSoTimeout(CONNECT_TIMEOUT_MS);
                String reply;
                try {
                    reply = readHead(farIn, MAX_RESPONSE_HEAD);
                } catch (HeadTooLarge | SocketTimeoutException bad) {
                    reply = null;
                }
                int status = reply == null ? -1 : status(reply);
                if (status != 200) {
                    LOG.info("web egress: upstream proxy refused CONNECT {}:{} (status {})", target.host(), target.port(), status);
                    respond(out, 502, "Bad Gateway", ERROR_HEADER + ": upstream", "The upstream proxy refused the connection.");
                    return;
                }
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            relay(socket, in, far, farIn);
        } finally {
            release(far);
        }
    }

    private void plain(Socket socket, InputStream in, OutputStream out, String method, String absolute, List<String> headers)
            throws IOException {
        URI uri;
        try {
            uri = new URI(absolute);
        } catch (URISyntaxException invalid) {
            respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "Not a valid URL.");
            return;
        }
        Optional<Target> parsed = uri.getRawAuthority() == null || uri.getRawUserInfo() != null ? Optional.empty()
                : target(uri.getHost(), String.valueOf(uri.getPort() < 0 ? 80 : uri.getPort()));
        if (parsed.isEmpty()) {
            respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "Not a valid URL.");
            return;
        }
        long length = 0;
        boolean seenLength = false;
        boolean hasHost = false;
        List<String> kept = new ArrayList<>();
        for (String line : headers) {
            int colon = line.indexOf(':');
            String rawName = colon <= 0 ? "" : line.substring(0, colon);
            if (!TOKEN.matcher(rawName).matches()) {
                // 行首空白（续行）、名字带空格：网站和出口可能认得不一样，正文边界就对不上
                respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "Malformed request header.");
                return;
            }
            String name = rawName.toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (name.equals("transfer-encoding") && !value.equalsIgnoreCase("identity")) {
                // 分块编码的请求正文要自己解析才知道在哪儿结束；浏览器的明文请求很少这样，直接不收。
                respond(out, 411, "Length Required", null, "Chunked request bodies are not supported.");
                return;
            }
            if (name.equals("content-length")) {
                if (seenLength) {
                    // 两个长度头时网站和出口可能各认一个，多出的字节就成了夹带的请求
                    respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "More than one Content-Length.");
                    return;
                }
                try {
                    length = DIGITS.matcher(value).matches() ? Long.parseLong(value) : -1;
                } catch (NumberFormatException invalid) {
                    length = -1;
                }
                if (length < 0) {
                    respond(out, 400, "Bad Request", ERROR_HEADER + ": bad-request", "Invalid Content-Length.");
                    return;
                }
                seenLength = true;
            }
            hasHost |= name.equals("host");
            if (!HOP_BY_HOP.contains(name) && !name.equals("transfer-encoding")) {
                kept.add(rawName + ": " + value);
            }
        }
        Target target = parsed.get();
        UpstreamProxy.Route route = upstream.route(target.host(), target.port(), false);
        Optional<Socket> remote = connect(target, route, out);
        if (remote.isEmpty()) {
            return;
        }
        Socket far = remote.get();
        Progress progress = new Progress();
        Thread watchdog = Thread.ofVirtual().name("egress-watchdog").start(() -> progress.watch(socket, far));
        try {
            String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            StringBuilder request = new StringBuilder(method).append(' ')
                    .append(route instanceof UpstreamProxy.Direct ? path : "http://" + target.authority() + path)
                    .append(" HTTP/1.1\r\n");
            if (!hasHost) {
                request.append("Host: ").append(uri.getPort() < 0 ? target.host() : target.authority()).append("\r\n");
            }
            for (String line : kept) {
                request.append(line).append("\r\n");
            }
            if (route instanceof UpstreamProxy.Via via && via.authorization() != null) {
                request.append("Proxy-Authorization: ").append(via.authorization()).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream farOut = far.getOutputStream();
            farOut.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            copy(in, farOut, length, progress);
            farOut.flush();
            far.setSoTimeout(POLL_MS);
            InputStream farIn = new BufferedInputStream(far.getInputStream());
            respondFrom(farIn, out, progress);
            // 客户端在这条连接上多发的字节一律丢掉：一条连接只转一个请求。
        } finally {
            watchdog.interrupt();
            release(far);
        }
    }

    /**
     * 明文请求的看门狗：Java 的套接字写没有超时，对方不读时写会一直卡住，读的空闲检查管不到。两个方向 5 分钟都没有进展、
     * 或者整个请求超过 10 分钟，就关掉两边，卡住的读写随之出错返回，名额空出来。
     */
    private static final class Progress {
        private final long started = System.nanoTime();
        private final AtomicLong last = new AtomicLong(started);

        void moved() {
            last.set(System.nanoTime());
        }

        void watch(Socket near, Socket far) {
            try {
                while (true) {
                    Thread.sleep(POLL_MS);
                    long now = System.nanoTime();
                    if (now - last.get() > IDLE_NANOS || now - started > PLAIN_LIFETIME_NANOS) {
                        quietly(near);
                        quietly(far);
                        return;
                    }
                }
            } catch (InterruptedException done) {
                // 请求转完了
            }
        }
    }

    /** 转发网站的回复：每个回复头都改成 {@code Connection: close}，再原样转正文直到网站关连接。 */
    private static void respondFrom(InputStream farIn, OutputStream out, Progress progress) throws IOException {
        long started = System.nanoTime();
        while (true) {
            String head;
            try {
                head = readHeadWaiting(farIn, started);
            } catch (HeadTooLarge tooLarge) {
                respond(out, 502, "Bad Gateway", ERROR_HEADER + ": invalid-response", "The site sent an invalid response.");
                return;
            }
            if (head == null || head.isEmpty()) {
                respond(out, 502, "Bad Gateway", ERROR_HEADER + ": invalid-response", "The site closed the connection without a response.");
                return;
            }
            List<String> lines = head.lines().toList();
            StringBuilder rewritten = new StringBuilder(lines.getFirst()).append("\r\n");
            for (String line : lines.subList(1, lines.size())) {
                int colon = line.indexOf(':');
                String name = colon <= 0 ? "" : line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                if (!name.equals("connection") && !name.equals("keep-alive") && !name.equals("proxy-connection")
                        && !name.equals(BLOCKED_HEADER.toLowerCase(Locale.ROOT)) && !name.equals(ERROR_HEADER.toLowerCase(Locale.ROOT))) {
                    rewritten.append(line).append("\r\n");
                }
            }
            rewritten.append("Connection: close\r\n\r\n");
            out.write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
            progress.moved();
            int status = status(head);
            if (status < 100 || status >= 200 || status == 101) {
                break;
            }
            // 1xx（如 103）之后还有正式的回复头，同样改写
        }
        byte[] buffer = new byte[16 * 1024];
        long last = System.nanoTime();
        while (true) {
            int n;
            try {
                n = farIn.read(buffer);
            } catch (SocketTimeoutException quiet) {
                if (System.nanoTime() - last > IDLE_NANOS) {
                    return;
                }
                continue;
            }
            if (n < 0) {
                out.flush();
                return;
            }
            out.write(buffer, 0, n);
            last = System.nanoTime();
            progress.moved();
        }
    }

    private static String readHeadWaiting(InputStream in, long started) throws IOException {
        while (true) {
            try {
                return readHead(in, MAX_RESPONSE_HEAD);
            } catch (SocketTimeoutException quiet) {
                if (System.nanoTime() - started > IDLE_NANOS) {
                    return null;
                }
            }
        }
    }

    /**
     * 解析一次，每个地址都检查；有一个被拦就整个拦下（回 403 带 {@link #BLOCKED_HEADER}）。然后按 IPv4 优先依次连这批地址，
     * 不再查 DNS。走上游代理时连代理，目标的 DNS 由代理解析（README 写明的剩余风险）。
     */
    private Optional<Socket> connect(Target target, UpstreamProxy.Route route, OutputStream out) throws IOException {
        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(target.bare()));
        } catch (UnknownHostException unresolved) {
            if (route instanceof UpstreamProxy.Via && !rules.blockPrivateNetwork()) {
                // 本机解析不了、有上游代理：交给代理解析（有的内网只有代理能查外网的 DNS）。开关打开时查不了就不放行。
                addresses = List.of();
            } else {
                LOG.debug("web egress could not resolve {}", target.host());
                respond(out, 502, "Bad Gateway", ERROR_HEADER + ": unresolved", "The host name could not be resolved.");
                return Optional.empty();
            }
        }
        Optional<AddressRules.Blocked> blocked = rules.firstBlocked(addresses, target.port());
        if (blocked.isPresent()) {
            AddressRules.Reason reason = blocked.get().reason();
            LOG.info("web egress blocked {}:{} ({})", target.host(), target.port(), reason.header());
            respond(out, 403, "Forbidden", BLOCKED_HEADER + ": " + reason.header(),
                    AddressRules.describe(target.host(), target.port(), blocked.get().address(), reason));
            return Optional.empty();
        }
        if (route instanceof UpstreamProxy.Via via) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(via.host(), via.port()), CONNECT_TIMEOUT_MS);
                open.add(socket);
                return Optional.of(socket);
            } catch (IOException failure) {
                socket.close();
                LOG.info("web egress could not reach the upstream proxy for {}:{} ({})", target.host(), target.port(),
                        failure.getClass().getSimpleName());
                respond(out, 502, "Bad Gateway", ERROR_HEADER + ": upstream", "The upstream proxy could not be reached.");
                return Optional.empty();
            }
        }
        List<InetAddress> ordered = new ArrayList<>(addresses);
        ordered.sort(Comparator.comparing(address -> address instanceof Inet4Address ? 0 : 1));
        IOException last = null;
        for (InetAddress address : ordered.subList(0, Math.min(ordered.size(), MAX_ADDRESSES_TRIED))) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, target.port()), CONNECT_TIMEOUT_MS);
                open.add(socket);
                return Optional.of(socket);
            } catch (IOException failure) {
                socket.close();
                last = failure;
            }
        }
        LOG.debug("web egress could not connect to {}:{} ({})", target.host(), target.port(),
                last == null ? "no address" : last.getClass().getSimpleName());
        respond(out, 502, "Bad Gateway", ERROR_HEADER + ": unreachable", "The site could not be reached.");
        return Optional.empty();
    }

    /** 连上的远端也登记，{@link #close} 时一起关；用完由调用方 {@link #release}。 */
    private void release(Socket far) {
        open.remove(far);
        quietly(far);
    }

    /** CONNECT 成功后两边互相转发，直到两边都关；5 分钟两个方向都没有数据就关。 */
    private static void relay(Socket near, InputStream nearIn, Socket far, InputStream farIn) throws IOException {
        AtomicLong last = new AtomicLong(System.nanoTime());
        near.setSoTimeout(POLL_MS);
        far.setSoTimeout(POLL_MS);
        Thread back = Thread.ofVirtual().name("egress-relay").start(() -> pump(farIn, near, far, last));
        pump(nearIn, far, near, last);
        try {
            back.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pump(InputStream from, Socket to, Socket source, AtomicLong last) {
        byte[] buffer = new byte[16 * 1024];
        try {
            OutputStream out = to.getOutputStream();
            while (true) {
                int n;
                try {
                    n = from.read(buffer);
                } catch (SocketTimeoutException quiet) {
                    if (System.nanoTime() - last.get() > IDLE_NANOS) {
                        quietly(to);
                        quietly(source);
                        return;
                    }
                    continue;
                }
                if (n < 0) {
                    to.shutdownOutput();
                    return;
                }
                out.write(buffer, 0, n);
                out.flush();
                last.set(System.nanoTime());
            }
        } catch (IOException broken) {
            quietly(to);
            quietly(source);
        }
    }

    private static void copy(InputStream in, OutputStream out, long length, Progress progress) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long left = length;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                throw new IOException("request body ended early");
            }
            out.write(buffer, 0, n);
            left -= n;
            progress.moved();
        }
    }

    /**
     * 主机名的最后一段是数字（或 {@code 0x…}）时必须是规范的 IPv4：{@code 0177.0.0.1}、{@code 2130706433} 这类写法，JDK 和上游代理
     * 可能解析成不同的地址（JDK 认作十进制，按 inet_aton 解析的代理认作八进制回环），检查的和连的就不是同一个。
     */
    private static Optional<Target> target(String host, String port) {
        if (host == null || !HOST.matcher(host).matches()) {
            return Optional.empty();
        }
        String lastLabel = host.substring(host.lastIndexOf('.') + 1);
        if (!host.startsWith("[") && NUMERIC_LABEL.matcher(lastLabel).matches() && !CANONICAL_IPV4.matcher(host).matches()) {
            return Optional.empty();
        }
        try {
            int number = Integer.parseInt(port);
            return number > 0 && number < 65536 ? Optional.of(new Target(host, number)) : Optional.empty();
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static int status(String head) {
        String[] parts = head.lines().findFirst().orElse("").split(" ");
        try {
            return parts.length < 2 ? -1 : Integer.parseInt(parts[1]);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static final class HeadTooLarge extends IOException {
        HeadTooLarge() {
            super("head too large");
        }
    }

    /** 读到空行为止，返回不含空行的头；还没读到任何字节就结束时返回 null。 */
    private static String readHead(InputStream in, int max) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (head.size() == 0) {
                    return null;
                }
                throw new IOException("head ended early");
            }
            head.write(b);
            if (head.size() > max) {
                throw new HeadTooLarge();
            }
            // 认 \r\n\r\n，也认 \n\n
            if (b == '\n') {
                matched++;
                if (matched == 2) {
                    return head.toString(StandardCharsets.ISO_8859_1).strip();
                }
            } else if (b != '\r') {
                matched = 0;
            }
        }
    }

    private static void respond(OutputStream out, int status, String reason, String header, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + (header == null ? "" : header + "\r\n")
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void quietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已经坏了
        }
    }
}
