package com.sahaj.platform.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientKeyTest {

    @Test
    void anIpv4AddressIsItsOwnKey() {
        assertThat(ClientKey.of("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientKey.of("203.0.113.8")).isNotEqualTo(ClientKey.of("203.0.113.7"));
    }

    @Test
    void addressesInTheSameIpv6SlashSixtyFourShareAKey() {
        String first = ClientKey.of("2001:db8:abcd:12:1111:2222:3333:4444");
        String second = ClientKey.of("2001:db8:abcd:12:ffff:eeee:dddd:cccc");
        assertThat(first).isEqualTo(second).startsWith("v6:");
        assertThat(ClientKey.of("2001:db8:abcd:13::1")).isNotEqualTo(first);
    }

    @Test
    void anIpv4MappedIpv6AddressIsTreatedAsIpv4() {
        assertThat(ClientKey.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void theLoopbackIpv6AddressWorks() {
        assertThat(ClientKey.of("0:0:0:0:0:0:0:1")).startsWith("v6:");
    }

    @Test
    void anythingThatIsNotAnIpLiteralSharesOneBucketAndIsNeverResolved() {
        assertThat(ClientKey.of("example.com")).isEqualTo(ClientKey.UNKNOWN);
        assertThat(ClientKey.of("999.1.1.1")).isEqualTo(ClientKey.UNKNOWN);
        assertThat(ClientKey.of("")).isEqualTo(ClientKey.UNKNOWN);
        assertThat(ClientKey.of(null)).isEqualTo(ClientKey.UNKNOWN);
    }
}
