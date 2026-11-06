package tantalum.shell.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 联网的地址规则（web-tools W6，检查方式照 OpenClaw {@code src/infra/net/ssrf.ts:192-195,266-363}）。按 DNS 解析后的真实地址判断，
 * 不看主机名。
 * <ul>
 *   <li>不论开关：Tantalum 自己的端口加本机地址一律拦下，免得模型用用户的身份调服务器接口。服务器绑在 {@code 0.0.0.0} 时，
 *       用局域网地址也绕不过去。</li>
 *   <li>开关打开时：回环、私网、链路本地（含云服务器元数据地址 {@code 169.254.169.254}）和 {@code 0.0.0.0}。只拦这几段，
 *       OpenClaw 多拦的段不拦。</li>
 * </ul>
 */
public final class AddressRules {

    /** 拦下的原因。{@link #header} 是出口回 403 时 {@code X-Tantalum-Blocked} 头的值。 */
    public enum Reason {
        PRIVATE_NETWORK("private-network"),
        OWN_PORT("own-port");

        private final String header;

        Reason(String header) {
            this.header = header;
        }

        public String header() {
            return header;
        }
    }

    private static final long INTERFACE_CACHE_NANOS = 60_000_000_000L;

    private final boolean blockPrivateNetwork;
    private volatile int serverPort = -1;
    private volatile int egressPort = -1;
    private volatile Set<InetAddress> interfaceAddresses = Set.of();
    private volatile long interfacesReadAt;
    private volatile boolean interfacesRead;

    public AddressRules(boolean blockPrivateNetwork) {
        this.blockPrivateNetwork = blockPrivateNetwork;
    }

    public boolean blockPrivateNetwork() {
        return blockPrivateNetwork;
    }

    /** Tantalum 服务器的端口。启动时取配置的 {@code server.port}，Web 服务器起来后改成实际端口（测试里是随机的）。 */
    public void ownPort(int port) {
        this.serverPort = port;
    }

    /** 出口自己的端口也算“自己的端口”：经出口再连出口，绕一圈就没人检查了。 */
    public void egressPort(int port) {
        this.egressPort = port;
    }

    public Optional<Reason> blocked(InetAddress address, int port) {
        InetAddress plain = unmapped(address);
        if ((port == serverPort || port == egressPort) && isLocal(plain)) {
            return Optional.of(Reason.OWN_PORT);
        }
        if (blockPrivateNetwork && isPrivate(plain)) {
            return Optional.of(Reason.PRIVATE_NETWORK);
        }
        return Optional.empty();
    }

    /** 任何一个地址被拦就整个拦下：域名同时解析到公网和内网时换不过去（照 OpenClaw {@code ssrf.ts:560-605}）。 */
    public Optional<Blocked> firstBlocked(List<InetAddress> addresses, int port) {
        for (InetAddress address : addresses) {
            Optional<Reason> reason = blocked(address, port);
            if (reason.isPresent()) {
                return Optional.of(new Blocked(address, reason.get()));
            }
        }
        return Optional.empty();
    }

    /** 被拦的地址和原因。 */
    public record Blocked(InetAddress address, Reason reason) {}

    /** 给模型的话，写明原因和下一步。 */
    public static String describe(String host, int port, InetAddress address, Reason reason) {
        String ip = unmapped(address).getHostAddress();
        return switch (reason) {
            case OWN_PORT -> "Blocked: " + bracketed(host) + ":" + port + " is the Tantalum server itself.";
            case PRIVATE_NETWORK -> "Blocked: " + (sameAddress(host, ip) ? ip + " is" : host + " resolves to " + ip + ",")
                    + " a private network address, and this server does not allow private network access.";
        };
    }

    private static String bracketed(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static boolean sameAddress(String host, String ip) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return bare.equalsIgnoreCase(ip);
    }

    /** IPv4 映射的 IPv6（{@code ::ffff:10.0.0.1}）先还原成 IPv4 再查，换个写法绕不过去。 */
    static InetAddress unmapped(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= bytes[i] == 0;
            }
            mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (mapped) {
                try {
                    return InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
                } catch (UnknownHostException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return address;
    }

    private static boolean isPrivate(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 127                                  // 回环 127.0.0.0/8
                    || first == 10                               // 10/8
                    || (first == 172 && second >= 16 && second <= 31) // 172.16/12
                    || (first == 192 && second == 168)           // 192.168/16
                    || (first == 169 && second == 254)           // 链路本地，含 169.254.169.254
                    || address.isAnyLocalAddress();              // 0.0.0.0
        }
        int first = b[0] & 0xff;
        return address.isLoopbackAddress()                       // ::1
                || address.isAnyLocalAddress()                   // ::
                || (first & 0xfe) == 0xfc                        // fc00::/7
                || (first == 0xfe && (b[1] & 0xc0) == 0x80);     // fe80::/10
    }

    private boolean isLocal(InetAddress address) {
        return address.isLoopbackAddress() || address.isAnyLocalAddress() || interfaces().contains(withoutScope(address));
    }

    /** 本机网卡上的地址，缓存 60 秒：每个连接都枚举网卡太慢，网卡地址也很少变。 */
    private Set<InetAddress> interfaces() {
        long now = System.nanoTime();
        if (!interfacesRead || now - interfacesReadAt > INTERFACE_CACHE_NANOS) {
            Set<InetAddress> found = new HashSet<>();
            try {
                for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                        found.add(withoutScope(address));
                    }
                }
            } catch (SocketException unreadable) {
                // 读不到网卡时只认回环和 0.0.0.0，下一次再读。
                return Set.of();
            }
            interfaceAddresses = Set.copyOf(found);
            interfacesReadAt = now;
            interfacesRead = true;
        }
        return interfaceAddresses;
    }

    /** 网卡上的 IPv6 地址带 scope（{@code fe80::1%en0}），解析出来的不带；去掉 scope 才比得上。 */
    private static InetAddress withoutScope(InetAddress address) {
        if (address instanceof Inet6Address six && six.getScopeId() != 0) {
            try {
                return InetAddress.getByAddress(six.getAddress());
            } catch (UnknownHostException impossible) {
                return address;
            }
        }
        return address;
    }
}
