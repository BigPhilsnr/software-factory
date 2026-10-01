package dev.softwarefactory.generation.tools;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

/** Decides whether an address is on the public internet, so agents cannot reach local or private networks. */
final class PublicAddresses {
    private static final int IPV4_LENGTH = 4;
    private static final int INT_BITS = 32;

    /** Reserved, private, shared, link-local, test and multicast IPv4 space. */
    private static final List<Block> NON_PUBLIC_V4 = List.of(
            block(0, 0, 0, 0, 8),
            block(10, 0, 0, 0, 8),
            block(100, 64, 0, 0, 10),
            block(127, 0, 0, 0, 8),
            block(169, 254, 0, 0, 16),
            block(172, 16, 0, 0, 12),
            block(192, 0, 0, 0, 16),
            block(192, 88, 99, 0, 24),
            block(192, 168, 0, 0, 16),
            block(198, 18, 0, 0, 15),
            block(198, 51, 100, 0, 24),
            block(203, 0, 113, 0, 24),
            block(224, 0, 0, 0, 3));

    /** 2000::/3, the only IPv6 space that is global unicast. */
    private static final Block GLOBAL_UNICAST_V6 = block(0x20, 0, 0, 0, 3);

    /** Transition/tunneling (2002::/16, 2001::/23) and documentation (2001:db8::/32, 3fff::/16) ranges. */
    private static final List<Block> NON_PUBLIC_V6 = List.of(
            block(0x20, 0x02, 0, 0, 16),
            block(0x20, 0x01, 0, 0, 23),
            block(0x20, 0x01, 0x0d, 0xb8, 32),
            block(0x3f, 0xff, 0, 0, 16));

    private PublicAddresses() {}

    /** A network given by the leading 32 bits of its addresses and a prefix length of at most 32. */
    private record Block(int network, int prefix) {
        boolean contains(int leadingBits) {
            return (leadingBits ^ network) >>> (INT_BITS - prefix) == 0;
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int leading = ByteBuffer.wrap(bytes, 0, IPV4_LENGTH).getInt();
        if (bytes.length == IPV4_LENGTH) return NON_PUBLIC_V4.stream().noneMatch(block -> block.contains(leading));
        return GLOBAL_UNICAST_V6.contains(leading)
                && NON_PUBLIC_V6.stream().noneMatch(block -> block.contains(leading));
    }

    private static Block block(int first, int second, int third, int fourth, int prefix) {
        return new Block(first << 24 | second << 16 | third << 8 | fourth, prefix);
    }
}
