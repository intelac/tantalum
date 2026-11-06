package tantalum.shell.net;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 联网工具能打开的网址（web-tools W2，照 DSH {@code web/web-fetch-http/src/policy.ts:26-55}）。{@code web_fetch} 的每一跳、
 * {@code browser_navigate} 都过这里，浏览器因此打不开 {@code file:}、{@code data:}、{@code javascript:}、{@code chrome:}。
 * 错误消息直接交给模型。
 * <p>中文、日文这类国际化域名先用 {@link IDN} 转成 {@code xn--} 写法，路径和查询里的非 ASCII 字符按 UTF-8 百分号编码，
 * 交给 {@code HttpClient} 和出口的都是纯 ASCII 的网址。主机名里有下划线的网址打不开：JDK 的 {@code URI} 认不出这样的主机，
 * {@code HttpClient} 也就发不出请求，所以直接说明原因。
 */
public final class WebUrl {

    public static final int MAX_LENGTH = 2048;

    private WebUrl() {}

    public static URI check(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Only http and https URLs are allowed.");
        }
        String trimmed = url.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The URL is longer than " + MAX_LENGTH + " characters.");
        }
        URI uri;
        try {
            uri = new URI(asciiHost(trimmed));
        } catch (URISyntaxException invalid) {
            // 先看协议：javascript:alert(1) 这类也常常解析不了，告诉模型协议不行比“格式不对”更准。
            if (!hasWebScheme(trimmed)) {
                throw new IllegalArgumentException("Only http and https URLs are allowed.");
            }
            throw new IllegalArgumentException("The URL is not valid: percent-encode spaces and special characters.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs are allowed.");
        }
        if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            throw new IllegalArgumentException("URLs with a user name or password are not allowed.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            if (uri.getRawAuthority() != null && uri.getRawAuthority().contains("_")) {
                throw new IllegalArgumentException("The URL is not valid: host names with an underscore (_) cannot be opened.");
            }
            throw new IllegalArgumentException("The URL is not valid: it has no host name.");
        }
        return URI.create(uri.toASCIIString());
    }

    /** 主机名里有非 ASCII 字符时换成 {@code xn--} 写法；IPv6 字面量、带 {@code @} 的（随后按用户名密码拒绝）原样留着。 */
    private static String asciiHost(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int start = scheme + 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String authority = url.substring(start, end);
        if (authority.contains("@") || authority.startsWith("[") || authority.chars().allMatch(c -> c < 0x80)) {
            return url;
        }
        int colon = authority.lastIndexOf(':');
        boolean port = colon >= 0 && authority.substring(colon + 1).chars().allMatch(Character::isDigit);
        String host = port ? authority.substring(0, colon) : authority;
        String ascii;
        try {
            ascii = IDN.toASCII(host);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("The URL is not valid: the host name is not a valid domain name.");
        }
        return url.substring(0, start) + ascii + (port ? authority.substring(colon) : "") + url.substring(end);
    }

    private static boolean hasWebScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
