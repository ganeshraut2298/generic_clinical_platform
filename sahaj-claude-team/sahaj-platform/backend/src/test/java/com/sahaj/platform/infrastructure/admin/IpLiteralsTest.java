package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpLiteralsTest {

    @Test
    void readsIpv4AndIpv6Literals() {
        assertThat(IpLiterals.parse("203.0.113.9")).isPresent();
        assertThat(IpLiterals.parse(" 10.0.0.1 ")).isPresent();
        assertThat(IpLiterals.parse("::1")).isPresent();
        assertThat(IpLiterals.parse("2001:db8::1")).isPresent();
        assertThat(IpLiterals.parse("fe80::1%eth0")).isPresent();
    }

    @Test
    void refusesHostNamesAndMalformedText() {
        assertThat(IpLiterals.parse("localhost")).isEmpty();
        assertThat(IpLiterals.parse("example.com")).isEmpty();
        assertThat(IpLiterals.parse("1.2.3")).isEmpty();
        assertThat(IpLiterals.parse("256.1.1.1")).isEmpty();
        assertThat(IpLiterals.parse("1.2.3.4.5")).isEmpty();
        assertThat(IpLiterals.parse("")).isEmpty();
        assertThat(IpLiterals.parse(null)).isEmpty();
        assertThat(IpLiterals.parse("'; drop table x; --")).isEmpty();
    }

    @Test
    void forDatabaseGivesPlainTextOrNull() {
        assertThat(IpLiterals.forDatabase("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(IpLiterals.forDatabase("fe80::1%eth0")).doesNotContain("%");
        assertThat(IpLiterals.forDatabase("not an ip")).isNull();
        assertThat(IpLiterals.forDatabase(null)).isNull();
    }
}
