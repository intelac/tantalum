package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.shell.net.AddressRules.Reason;

/** W6、需求 4.1、4.2、4.4：开关开、关各一遍；自己的端口不论开关都拦。 */
class AddressRulesTest {

    private static final int OWN = 8091;

    /** 地址和开关打开时该不该拦。开关关着时一个都不拦（需求 4.1）。 */
    static Stream<Arguments> addresses() {
        return Stream.of(
                Arguments.of("10.0.0.1", true),
                Arguments.of("172.31.255.255", true),
                Arguments.of("172.32.0.1", false),
                Arguments.of("172.15.255.255", false),
                Arguments.of("192.168.1.1", true),
                Arguments.of("169.254.169.254", true),
                Arguments.of("127.0.0.2", true),
                Arguments.of("0.0.0.0", true),
                Arguments.of("::1", true),
                Arguments.of("fe80::1", true),
                Arguments.of("fd00::1", true),
                Arguments.of("::ffff:10.0.0.1", true),
                Arguments.of("8.8.8.8", false),
                Arguments.of("2001:4860:4860::8888", false));
    }

    @ParameterizedTest
    @MethodSource("addresses")
    void privateRangesAreBlockedOnlyWhenTheSwitchIsOn(String literal, boolean blockedWhenOn) throws Exception {
        InetAddress address = InetAddress.getByName(literal);
        Optional<Reason> expected = blockedWhenOn ? Optional.of(Reason.PRIVATE_NETWORK) : Optional.empty();
        assertEquals(expected, new AddressRules(true).blocked(address, 443), literal);
        assertEquals(Optional.empty(), new AddressRules(false).blocked(address, 443), literal);
    }

    /** JDK 自己会把 {@code ::ffff:a.b.c.d} 字面量变成 IPv4；直接构造的 IPv6 对象也要先还原，换个写法绕不过去。 */
    @Test
    void mappedIpv6ObjectsAreUnmappedFirst() throws Exception {
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 10, 0, 0, 1};
        InetAddress six = Inet6Address.getByAddress(null, mapped, -1);
        assertInstanceOf(Inet6Address.class, six);
        assertEquals(Optional.of(Reason.PRIVATE_NETWORK), new AddressRules(true).blocked(six, 443));
        byte[] loopback = mapped.clone();
        loopback[12] = 127;
        AddressRules rules = new AddressRules(false);
        rules.ownPort(OWN);
        assertEquals(Optional.of(Reason.OWN_PORT), rules.blocked(Inet6Address.getByAddress(null, loopback, -1), OWN));
    }

    @Test
    void ownPortIsBlockedOnLocalAddressesWhateverTheSwitch() throws Exception {
        for (boolean block : List.of(false, true)) {
            AddressRules rules = new AddressRules(block);
            rules.ownPort(OWN);
            rules.egressPort(9000);
            for (String local : List.of("127.0.0.1", "127.0.0.2", "::1", "0.0.0.0", "::")) {
                assertEquals(Optional.of(Reason.OWN_PORT), rules.blocked(InetAddress.getByName(local), OWN), local);
            }
            assertEquals(Optional.of(Reason.OWN_PORT), rules.blocked(InetAddress.getByName("127.0.0.1"), 9000), "egress port");
            Optional<Reason> otherPort = rules.blocked(InetAddress.getByName("127.0.0.1"), OWN + 1);
            assertEquals(block ? Optional.of(Reason.PRIVATE_NETWORK) : Optional.empty(), otherPort, "other port");
            assertEquals(Optional.empty(), rules.blocked(InetAddress.getByName("8.8.8.8"), OWN), "same port elsewhere");
        }
    }

    /** 服务器绑在 0.0.0.0 时，用本机局域网地址也绕不过去。 */
    @Test
    void ownPortIsBlockedOnThisMachinesInterfaceAddresses() throws Exception {
        InetAddress nic = null;
        for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(face.getInetAddresses())) {
                if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    nic = address;
                }
            }
        }
        assumeTrue(nic != null, "this machine has no non-loopback interface address");
        AddressRules rules = new AddressRules(false);
        rules.ownPort(OWN);
        InetAddress resolved = InetAddress.getByAddress(nic.getAddress());
        assertEquals(Optional.of(Reason.OWN_PORT), rules.blocked(resolved, OWN), resolved.toString());
        assertEquals(Optional.empty(), rules.blocked(resolved, OWN + 1));
    }

    @Test
    void anyBlockedAddressBlocksTheWholeName() throws Exception {
        AddressRules rules = new AddressRules(true);
        List<InetAddress> mixed = List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.5"));
        assertEquals(Optional.of(new AddressRules.Blocked(InetAddress.getByName("10.0.0.5"), Reason.PRIVATE_NETWORK)),
                rules.firstBlocked(mixed, 443));
        assertEquals(Optional.empty(), rules.firstBlocked(List.of(InetAddress.getByName("8.8.8.8")), 443));
    }

    @Test
    void descriptionsFollowTheDesignWording() throws Exception {
        assertEquals("Blocked: example.internal resolves to 10.0.0.5, a private network address, and this server does not allow "
                        + "private network access.",
                AddressRules.describe("example.internal", 443, InetAddress.getByName("10.0.0.5"), Reason.PRIVATE_NETWORK));
        assertEquals("Blocked: 10.0.0.5 is a private network address, and this server does not allow private network access.",
                AddressRules.describe("10.0.0.5", 443, InetAddress.getByName("10.0.0.5"), Reason.PRIVATE_NETWORK));
        assertEquals("Blocked: 127.0.0.1:8091 is the Tantalum server itself.",
                AddressRules.describe("127.0.0.1", OWN, InetAddress.getByName("127.0.0.1"), Reason.OWN_PORT));
        assertEquals("Blocked: [::1]:8091 is the Tantalum server itself.",
                AddressRules.describe("::1", OWN, InetAddress.getByName("::1"), Reason.OWN_PORT));
    }
}
