package tantalum.shell.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 联网工具能打开的网址（web-tools W2，照 DSH {@code web/web-fetch-http/src/policy.ts:26-55}）。{@code web_fetch} 的每一跳、
 * {@code browser_navigate} 都过这里，浏览器因此打不开 {@code file:}、{@code data:}、{@code javascript:}、{@code chrome:}。
 * 错误消息直接交给模型。
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
            uri = new URI(trimmed);
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
            throw new IllegalArgumentException("The URL is not valid: it has no host name.");
        }
        return uri;
    }

    private static boolean hasWebScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
