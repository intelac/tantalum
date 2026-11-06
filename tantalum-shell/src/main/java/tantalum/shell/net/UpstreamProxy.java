package tantalum.shell.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 出口后面的上游代理（web-tools W12，照 DSH {@code .agents/notes/implemented/architecture/2026-08-27-outbound-proxy-policy.md}）。
 * 启动时读一次 {@code HTTPS_PROXY}、{@code HTTP_PROXY}、{@code NO_PROXY}，大小写都认，小写优先；空值当没设（note:35）。
 * SOCKS 或解析不了的值记一行 WARN，这个协议改为直连，不改走别的代理（note:37）。回环地址永远不走代理。
 * 代理的用户名密码只在内存里：不进日志，{@link Via#toString} 也不带。
 */
public final class UpstreamProxy {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamProxy.class);

    /** 怎么连到目标。 */
    public sealed interface Route permits Direct, Via {}

    /** 直接连目标。 */
    public record Direct() implements Route {}

    /** 经上游代理。{@code authorization} 是 {@code Proxy-Authorization} 头的值，代理网址里没有用户名时为 null。 */
    public record Via(String host, int port, String authorization) implements Route {

        @Override
        public String toString() {
            return "Via[" + host + ":" + port + (authorization == null ? "" : ", with credentials") + "]";
        }
    }

    private static final Direct DIRECT = new Direct();

    private final Via https;
    private final Via http;
    private final List<String> noProxy;

    private UpstreamProxy(Via https, Via http, List<String> noProxy) {
        this.https = https;
        this.http = http;
        this.noProxy = noProxy;
    }

    /** 不用上游代理。 */
    public static UpstreamProxy none() {
        return new UpstreamProxy(null, null, List.of());
    }

    public static UpstreamProxy fromEnvironment(Function<String, String> env) {
        Via https = parse("HTTPS_PROXY", value(env, "https_proxy", "HTTPS_PROXY"));
        Via http = parse("HTTP_PROXY", value(env, "http_proxy", "HTTP_PROXY"));
        List<String> noProxy = new ArrayList<>();
        String list = value(env, "no_proxy", "NO_PROXY");
        if (list != null) {
            for (String entry : list.split("[,\\s]+")) {
                if (!entry.isBlank()) {
                    noProxy.add(stripPort(entry.strip().toLowerCase(Locale.ROOT)));
                }
            }
        }
        return new UpstreamProxy(https, http, List.copyOf(noProxy));
    }

    /** {@code tunnel} 为真是 CONNECT（https），否则是明文 http 请求。 */
    public Route route(String host, int port, boolean tunnel) {
        Via proxy = tunnel ? https : http;
        if (proxy == null || Hosts.isLoopback(host) || excluded(host)) {
            return DIRECT;
        }
        return proxy;
    }

    private boolean excluded(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        for (String entry : noProxy) {
            if (entry.equals("*")) {
                return true;
            }
            String bare = entry.startsWith(".") ? entry.substring(1) : entry;
            if (name.equals(bare) || name.endsWith("." + bare)) {
                return true;
            }
        }
        return false;
    }

    private static String value(Function<String, String> env, String lower, String upper) {
        String value = env.apply(lower);
        if (value == null || value.isBlank()) {
            value = env.apply(upper);
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 只认 {@code http://}（或者不写协议）。日志只写变量名，不写值：值里可能有用户名密码。 */
    private static Via parse(String variable, String value) {
        if (value == null) {
            return null;
        }
        String withScheme = value.contains("://") ? value : "http://" + value;
        try {
            URI uri = new URI(withScheme);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http")) {
                LOG.warn("{} uses an unsupported proxy scheme (only http:// proxies are supported); connecting directly instead",
                        variable);
                return null;
            }
            if (uri.getHost() == null) {
                throw new URISyntaxException(variable, "no host");
            }
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            return new Via(uri.getHost(), port, authorization(uri.getRawUserInfo()));
        } catch (URISyntaxException | IllegalArgumentException unreadable) {
            LOG.warn("{} could not be read as a proxy URL; connecting directly instead", variable);
            return null;
        }
    }

    private static String authorization(String rawUserInfo) {
        if (rawUserInfo == null || rawUserInfo.isEmpty()) {
            return null;
        }
        int colon = rawUserInfo.indexOf(':');
        String user = colon < 0 ? rawUserInfo : rawUserInfo.substring(0, colon);
        String password = colon < 0 ? "" : rawUserInfo.substring(colon + 1);
        String plain = percentDecoded(user) + ":" + percentDecoded(password);
        return "Basic " + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** 网址里的用户名密码只做百分号解码；{@code +} 是字面的加号，不是空格。 */
    private static String percentDecoded(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** {@code NO_PROXY} 里的 {@code host:port} 只按主机比较；IPv6 字面量不动。 */
    private static String stripPort(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon > 0 && entry.indexOf(':') == colon && entry.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            return entry.substring(0, colon);
        }
        return entry;
    }
}
