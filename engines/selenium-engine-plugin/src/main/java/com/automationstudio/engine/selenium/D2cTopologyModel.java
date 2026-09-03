package com.automationstudio.engine.selenium;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed value vocabulary for the non-executable D2c topology. */
final class D2cTopologyModel {
    private D2cTopologyModel() {}

    enum NetworkInstance { WORKER_NETWORK, EGRESS_NETWORK }
    enum EndpointInstance { WORKER_ENDPOINT, GATEWAY_WORKER_ENDPOINT, GATEWAY_EGRESS_ENDPOINT }

    record ReservationKey(UUID executionId, long topologyGeneration, NetworkInstance network) {
        ReservationKey {
            Objects.requireNonNull(executionId); Objects.requireNonNull(network);
            if (topologyGeneration <= 0) throw new IllegalArgumentException("Invalid topology generation");
        }
    }

    record Ipv4Cidr(int network, int prefix) implements Comparable<Ipv4Cidr> {
        Ipv4Cidr {
            if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("Invalid prefix");
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            if ((network & mask) != network) throw new IllegalArgumentException("Non-canonical CIDR");
        }
        static Ipv4Cidr parse(String value) {
            if (value == null || !value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}"))
                throw new IllegalArgumentException("Invalid IPv4 CIDR");
            String[] parts = value.split("/");
            try {
                byte[] raw = InetAddress.getByName(parts[0]).getAddress();
                if (!(InetAddress.getByAddress(raw) instanceof Inet4Address)) throw new IllegalArgumentException();
                int address = 0; for (byte octet : raw) address = address << 8 | Byte.toUnsignedInt(octet);
                return new Ipv4Cidr(address, Integer.parseInt(parts[1]));
            } catch (UnknownHostException | NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid IPv4 CIDR", failure);
            }
        }
        boolean overlaps(Ipv4Cidr other) {
            int common = Math.min(prefix, other.prefix);
            int mask = common == 0 ? 0 : -1 << (32 - common);
            return (network & mask) == (other.network & mask);
        }
        String address(int offset) {
            long value = Integer.toUnsignedLong(network) + offset;
            if (offset < 0 || value > Integer.toUnsignedLong(network | ~(-1 << (32 - prefix))))
                throw new IllegalArgumentException("Address outside CIDR");
            return String.format("%d.%d.%d.%d", value >>> 24, value >>> 16 & 255, value >>> 8 & 255, value & 255);
        }
        @Override public String toString() { return address(0) + "/" + prefix; }
        @Override public int compareTo(Ipv4Cidr other) { return Integer.compareUnsigned(network, other.network); }
    }

    record Allocation(ReservationKey key, Ipv4Cidr subnet, String bridgeGateway,
            List<String> endpointAddresses) {
        Allocation {
            Objects.requireNonNull(key); Objects.requireNonNull(subnet); Objects.requireNonNull(bridgeGateway);
            endpointAddresses = List.copyOf(endpointAddresses);
            if (subnet.prefix() != 28 || !bridgeGateway.equals(subnet.address(1)))
                throw new IllegalArgumentException("Noncanonical allocation");
        }
    }
}
