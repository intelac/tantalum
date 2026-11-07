package tantalum.shell.fetch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.WebUrl;

/**
 * 下载一个网页（web-tools 设计“一次 {@code web_fetch}”1–7）：每一跳先过 {@link WebUrl} 和 {@link AddressRules}（只为给模型一句清楚的原因，
 * 真正拦的是出口），经 {@link Egress#client()} 发 {@code GET}，最多跟 5 次跳转，按 {@code Content-Type} 分成 HTML、文本和拒绝，
 * 流式读满 5 MB 就停，整次（含跳转）30 秒。
 * <p>常量由产品统一定，不做配置（DSH 也刻意不给这些参数，web-capability-seam note:212、311-313）。不记日志：网址的路径、正文都不进日志。
 */
public final class Fetcher {

    static final int MAX_REDIRECTS = 5;
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final int BLOCKED_BODY = 4096;
    private static final String USER_AGENT = "Tantalum/" + version();
    private static final String ACCEPT = "text/html,application/xhtml+xml,text/plain;q=0.9,application/json;q=0.9,"
            + "application/xml;q=0.9,text/*;q=0.8,*/*;q=0.1";

    /** 下载的结果。{@code text} 是 HTML 转好的 Markdown 或原样的文本；{@code cutAt5Mb} 表示下载到 5 MB 停了。 */
    public record Fetched(URI finalUrl, int status, String contentType, String text, boolean cutAt5Mb) {}

    /** 下载失败：连不上、超时、内容类型不收、被拦下。消息直接给模型。 */
    public static final class FetchFailed extends Exception {
        public FetchFailed(String message) {
            super(message);
        }
    }

    private final HttpClient client;
    private final AddressRules rules;

    public Fetcher(Egress egress, AddressRules rules) {
        this.client = egress.client();
        this.rules = rules;
    }

    public Fetched fetch(URI url) throws FetchFailed, InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        URI current = url;
        for (int redirects = 0; ; redirects++) {
            boolean resolved = precheck(current, deadline);
            Response response = get(current, deadline, resolved);
            Optional<String> location = response.redirect();
            if (location.isEmpty()) {
                return read(current, response);
            }
            if (redirects == MAX_REDIRECTS) {
                throw new FetchFailed("Too many redirects (more than " + MAX_REDIRECTS + ").");
            }
            current = next(current, location.get());
        }
    }

    /** 跳转的目标同样要过 {@link WebUrl}：跳到 {@code file:}、带密码的网址都停下。 */
    private static URI next(URI current, String location) throws FetchFailed {
        try {
            URI target = current.resolve(new URI(location.strip().replace(" ", "%20")));
            return WebUrl.check(target.toString());
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            String reason = invalid instanceof IllegalArgumentException ? " " + invalid.getMessage() : "";
            throw new FetchFailed("The page redirected to a URL that cannot be fetched." + reason);
        }
    }

    /**
     * 解析网址里的主机，按地址规则先查一遍，给模型清楚的原因。解析不了时返回 false、不报错：有上游代理时由代理解析，出口会兜底。
     */
    private boolean precheck(URI url, long deadline) throws FetchFailed, InterruptedException {
        String host = url.getHost();
        int port = port(url);
        List<InetAddress> addresses;
        // DNS 没有超时参数，放到自己的线程上等，最多等到这次抓取的时限（review-fixes 需求 11.8）
        FutureTask<InetAddress[]> lookup = new FutureTask<>(() -> InetAddress.getAllByName(host));
        Thread.ofVirtual().name("fetch-dns").start(lookup);
        try {
            addresses = List.of(lookup.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof UnknownHostException) {
                return false;
            }
            throw new FetchFailed("Could not fetch " + url + ": " + egressReason("unresolved"));
        } catch (TimeoutException slow) {
            lookup.cancel(true);
            throw new FetchFailed("Could not fetch " + url + ": the host name could not be resolved in time");
        }
        Optional<AddressRules.Blocked> blocked = rules.firstBlocked(addresses, port);
        if (blocked.isPresent()) {
            throw new FetchFailed(AddressRules.describe(host, port, blocked.get().address(), blocked.get().reason()));
        }
        return true;
    }

    private static int port(URI url) {
        if (url.getPort() >= 0) {
            return url.getPort();
        }
        return url.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    /** 回复头加读到的正文。{@code body} 只在不是跳转、类型能收的时候读。 */
    private record Response(int status, HttpHeaders headers, Body body) {

        Optional<String> redirect() {
            return followed(status) ? headers.firstValue("location") : Optional.empty();
        }

        /** 跟的跳转；300、305 这类不跟，正文照常返回。 */
        static boolean followed(int status) {
            return switch (status) {
                case 301, 302, 303, 307, 308 -> true;
                default -> false;
            };
        }

        String contentType() {
            return headers.firstValue("content-type").orElse("");
        }
    }

    private record Body(byte[] bytes, boolean cut) {}

    private Response get(URI url, long deadline, boolean resolved) throws FetchFailed, InterruptedException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw timedOut(url);
        }
        URI withoutFragment = withoutFragment(url);
        HttpRequest request = HttpRequest.newBuilder(withoutFragment).GET().timeout(Duration.ofNanos(left))
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .build();
        // 出口的两个头只在明文请求上可信：https 的回复在隧道里，出口看不到也改不了，网站可以自己写这两个头冒充出口。
        boolean plain = url.getScheme().equalsIgnoreCase("http");
        CompletableFuture<HttpResponse<Body>> pending = client.sendAsync(request, info -> new LimitedBody(limit(info, plain)));
        HttpResponse<Body> response;
        try {
            response = pending.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            pending.cancel(true);
            throw timedOut(url);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            throw failure(url, failed.getCause(), resolved);
        }
        if (plain && response.statusCode() == 403 && response.headers().firstValue(Egress.BLOCKED_HEADER).isPresent()) {
            // 出口拦下的（跳转后、DNS 换了地址）：当错误，不当网站的 403。正文是出口自己写的原因。
            throw new FetchFailed(new String(response.body().bytes(), StandardCharsets.UTF_8));
        }
        Optional<String> egressError = plain ? response.headers().firstValue(Egress.ERROR_HEADER) : Optional.empty();
        if (egressError.isPresent()) {
            String reason = !resolved && egressError.get().equals("unreachable") ? "unresolved" : egressError.get();
            throw new FetchFailed("Could not fetch " + url + ": " + egressReason(reason) + ".");
        }
        return new Response(response.statusCode(), response.headers(), response.body());
    }

    /** 跳转和不收的类型不读正文；出口的拦截说明只读一点。 */
    private static int limit(HttpResponse.ResponseInfo info, boolean plain) {
        int status = info.statusCode();
        if (plain && status == 403 && info.headers().firstValue(Egress.BLOCKED_HEADER).isPresent()) {
            return BLOCKED_BODY;
        }
        if (plain && info.headers().firstValue(Egress.ERROR_HEADER).isPresent()) {
            return 0;
        }
        if (Response.followed(status) && info.headers().firstValue("location").isPresent()) {
            return 0;
        }
        return kind(info.headers().firstValue("content-type").orElse("")) == Kind.REFUSED ? 0 : MAX_BYTES;
    }

    private static URI withoutFragment(URI url) {
        if (url.getRawFragment() == null) {
            return url;
        }
        String text = url.toString();
        return URI.create(text.substring(0, text.indexOf('#')));
    }

    private enum Kind { HTML, TEXT, REFUSED }

    /** 照 DSH {@code web-fetch-http/src/policy.ts:78-84}。没写类型的当 HTML：jsoup 对纯文本也解析得了。 */
    private static Kind kind(String contentType) {
        String type = mediaType(contentType);
        if (type.isEmpty() || type.equals("text/html") || type.equals("application/xhtml+xml")) {
            return Kind.HTML;
        }
        if (type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml")
                || type.endsWith("+json") || type.endsWith("+xml")) {
            return Kind.TEXT;
        }
        return Kind.REFUSED;
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    private Fetched read(URI url, Response response) throws FetchFailed {
        String contentType = response.contentType();
        String type = mediaType(contentType);
        Kind kind = kind(contentType);
        if (kind == Kind.REFUSED) {
            if (type.startsWith("image/")) {
                throw new FetchFailed("This is an image (" + type + "). Open it with browser_navigate and look at it with "
                        + "browser_take_screenshot.");
            }
            if (type.equals("application/pdf")) {
                throw new FetchFailed("PDF files cannot be read yet: web_fetch does not convert them and the browser refuses downloads.");
            }
            throw new FetchFailed("Unsupported content type " + type + ": web_fetch reads HTML, plain text, JSON and XML.");
        }
        Body body = response.body();
        Charset declared = charset(contentType);
        String text;
        if (kind == Kind.HTML) {
            text = HtmlMarkdown.of(parse(body.bytes(), declared, url));
        } else {
            text = new String(body.bytes(), declared == null ? StandardCharsets.UTF_8 : declared);
        }
        return new Fetched(url, response.status(), contentType, text, body.cut());
    }

    /** 编码：头里写了、JDK 认识就用它；否则交给 jsoup（BOM、{@code <meta charset>}、{@code http-equiv}），都没有就是 UTF-8。 */
    private static Document parse(byte[] bytes, Charset declared, URI url) throws FetchFailed {
        try {
            return Jsoup.parse(new ByteArrayInputStream(bytes), declared == null ? null : declared.name(), url.toString());
        } catch (IOException unreadable) {
            throw new FetchFailed("Could not read the page from " + url + ".");
        }
    }

    private static Charset charset(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.strip();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = trimmed.substring(8).strip().replace("\"", "").replace("'", "");
                try {
                    return Charset.forName(name);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
                    return null;
                }
            }
        }
        return null;
    }

    private static FetchFailed timedOut(URI url) {
        return new FetchFailed("Could not fetch " + url + ": timed out after " + DEADLINE.toSeconds() + " s.");
    }

    private static String egressReason(String category) {
        return switch (category) {
            case "unresolved" -> "the host name could not be resolved";
            case "unreachable" -> "the site could not be reached";
            case "upstream" -> "the upstream proxy refused the connection or could not be reached";
            case "invalid-response" -> "the site sent an invalid response";
            case "bad-request" -> "this server refused the request as malformed or ambiguous";
            case "busy" -> "this server has too many open web connections; try again shortly";
            default -> "the connection failed";
        };
    }

    /** 原因只写类别：连接被拒、超时、TLS 失败、被出口拦下。 */
    private static FetchFailed failure(URI url, Throwable cause, boolean resolved) {
        String message = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        String reason;
        if (!resolved && (message.contains("Tunnel failed, got: 502") || cause instanceof ConnectException)) {
            // 本机解析不了、出口也连不上：多半是名字写错了（有上游代理时本机解析不了是正常的，别的错误照实写）
            reason = "the host name could not be resolved";
        } else if (cause instanceof HttpConnectTimeoutException) {
            reason = "the connection timed out";
        } else if (cause instanceof HttpTimeoutException) {
            reason = "timed out after " + DEADLINE.toSeconds() + " s";
        } else if (cause instanceof ConnectException) {
            reason = "connection refused";
        } else if (message.contains("Tunnel failed, got: 403")) {
            reason = "blocked by this server's network rules";
        } else if (message.contains("Tunnel failed")) {
            reason = "the site could not be reached";
        } else if (cause instanceof SSLException) {
            reason = "the TLS handshake failed";
        } else {
            reason = "the connection failed";
        }
        return new FetchFailed("Could not fetch " + url + ": " + reason + ".");
    }

    /** 收正文，收满 {@code limit} 字节就取消订阅（HTTP/1.1 关掉连接，HTTP/2 只结束这个 stream），记下“下载停了”。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<Body> {

        /**
         * 不要正文时最多读这么多再丢掉。取消订阅在 HTTP/2 上和 JDK 交付回复有竞争：正文已经交出，回复照样可能被当成
         * “Stream N cancelled” 失败（{@code FetcherTlsIT} 约四次一次，跳转的 https 网址报“连接失败”）。跳转和出错页的正文都很小，
         * 读完就不用取消。
         */
        private static final int DISCARD_LIMIT = 64 * 1024;

        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long discarded;
        private final CompletableFuture<Body> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            if (limit == 0) {
                // 不要正文（跳转、不收的类型）：小的读完丢掉，正常结束；只有大的才取消（见 stop）
                items.forEach(item -> discarded += item.remaining());
                if (discarded > DISCARD_LIMIT) {
                    stop(new Body(new byte[0], false));
                    return;
                }
                subscription.request(1);
                return;
            }
            for (ByteBuffer item : items) {
                int room = limit - bytes.size();
                if (item.remaining() > room) {
                    byte[] part = new byte[room];
                    item.get(part);
                    bytes.writeBytes(part);
                    stop(new Body(bytes.toByteArray(), true));
                    return;
                }
                byte[] all = new byte[item.remaining()];
                item.get(all);
                bytes.writeBytes(all);
            }
            subscription.request(1);
        }

        /**
         * 先交出正文，再取消订阅。顺序不能反：HTTP/2（https 经隧道时通常是它）上取消会结束这个 stream，正文还没交出的话，
         * JDK 把整个回复当成失败（“Stream 1 cancelled”）。2026-11-07 8091 上所有回跳转的 https 网址都因此报“连接失败”，
         * 明文 http（HTTP/1.1）没有这个问题；{@code FetcherTlsIT} 钉住。
         */
        private void stop(Body body) {
            result.complete(body);
            subscription.cancel();
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(new Body(limit == 0 ? new byte[0] : bytes.toByteArray(), false));
        }

        @Override
        public java.util.concurrent.CompletionStage<Body> getBody() {
            return result;
        }
    }

    private static String version() {
        String version = Fetcher.class.getPackage().getImplementationVersion();
        return version == null ? "0.1.0" : version;
    }
}
