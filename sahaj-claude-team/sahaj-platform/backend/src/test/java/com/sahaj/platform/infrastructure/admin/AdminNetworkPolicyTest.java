package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminNetworkPolicyTest {

    @Test
    void emptyListAllowsEveryoneInDevOnly() {
        assertThat(new AdminNetworkPolicy("", true).permits("203.0.113.9")).isTrue();
        assertThat(new AdminNetworkPolicy(null, true).permits("127.0.0.1")).isTrue();
        assertThat(new AdminNetworkPolicy("", false).permits("127.0.0.1")).isFalse();
        assertThat(new AdminNetworkPolicy("  , ", false).permits("10.1.2.3")).isFalse();
    }

    @Test
    void ipv4CidrMatching() {
        AdminNetworkPolicy policy = new AdminNetworkPolicy("10.0.0.0/8, 203.0.113.0/24", false);
        assertThat(policy.permits("10.200.1.1")).isTrue();
        assertThat(policy.permits("203.0.113.77")).isTrue();
        assertThat(policy.permits("203.0.114.1")).isFalse();
        assertThat(policy.permits("11.0.0.1")).isFalse();
    }

    @Test
    void aSingleAddressMeansAHostRoute() {
        AdminNetworkPolicy policy = new AdminNetworkPolicy("198.51.100.7", false);
        assertThat(policy.permits("198.51.100.7")).isTrue();
        assertThat(policy.permits("198.51.100.8")).isFalse();
    }

    @Test
    void nonByteAlignedPrefix() {
        AdminNetworkPolicy policy = new AdminNetworkPolicy("192.168.4.0/22", false);
        assertThat(policy.permits("192.168.7.255")).isTrue();
        assertThat(policy.permits("192.168.8.0")).isFalse();
    }

    @Test
    void ipv6Cidr() {
        AdminNetworkPolicy policy = new AdminNetworkPolicy("2001:db8::/32", false);
        assertThat(policy.permits("2001:db8:1::5")).isTrue();
        assertThat(policy.permits("2001:db9::1")).isFalse();
        assertThat(policy.permits("10.0.0.1")).isFalse();
    }

    @Test
    void anIpv4MappedIpv6AddressIsComparedAsIpv4() {
        assertThat(new AdminNetworkPolicy("10.0.0.0/8", false).permits("::ffff:10.1.1.1")).isTrue();
    }

    @Test
    void anAddressThatIsNotAnIpLiteralIsRefusedAndNeverResolved() {
        AdminNetworkPolicy policy = new AdminNetworkPolicy("0.0.0.0/0", false);
        assertThat(policy.permits("localhost")).isFalse();
        assertThat(policy.permits("example.com")).isFalse();
        assertThat(policy.permits(null)).isFalse();
        assertThat(policy.permits("")).isFalse();
        assertThat(policy.permits("1.2.3")).isFalse();
    }

    @Test
    void invalidEntriesStopStartup() {
        assertThatThrownBy(() -> new AdminNetworkPolicy("10.0.0.0/33", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdminNetworkPolicy("example.com", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdminNetworkPolicy("10.0.0.0/x", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdminNetworkPolicy("10.0.0.0/-1", false)).isInstanceOf(IllegalStateException.class);
    }
}
