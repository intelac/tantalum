package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** QAgent 问题清单第 22 条（2026-11-14 Yi 发来）：NO_PROXY 里写网段、写成网址（带协议或路径）的条目不认，内网地址还是走了代理。 */
class QAgentProxyIssuesTest {

    private static UpstreamProxy.Route route(String noProxy, String host) {
        return UpstreamProxy.fromEnvironment(Map.of("HTTPS_PROXY", "http://p:1", "NO_PROXY", noProxy)::get).route(host, 443, true);
    }

    @Test
    void issue22_aCidrRangeIsHonoured() {
        assertEquals(new UpstreamProxy.Direct(), route("10.0.0.0/8", "10.1.2.3"));
        assertEquals(new UpstreamProxy.Direct(), route("192.168.0.0/16", "192.168.5.9"));
    }

    @Test
    void issue22_anEntryWrittenAsAUrlIsHonoured() {
        assertEquals(new UpstreamProxy.Direct(), route("https://jira.corp.example/", "jira.corp.example"));
        assertEquals(new UpstreamProxy.Direct(), route("wiki.corp.example/confluence", "wiki.corp.example"));
    }

    /** 网段只认 IP 字面量：范围外的地址、长得像的主机名照样走代理；IPv6 网段也认。 */
    @Test
    void cidrRangesDoNotOverreach() {
        assertEquals(new UpstreamProxy.Via("p", 1, null), route("10.0.0.0/8", "11.0.0.1"));
        assertEquals(new UpstreamProxy.Via("p", 1, null), route("10.0.0.0/8", "10.example.com"));
        assertEquals(new UpstreamProxy.Direct(), route("fd00::/8", "[fd12::1]"));
        assertEquals(new UpstreamProxy.Via("p", 1, null), route("fd00::/8", "[fe80::1]"));
        assertEquals(new UpstreamProxy.Direct(), route("http://10.1.2.3:8080/x", "10.1.2.3"));
    }
}
