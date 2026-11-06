package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The strict 8-4-4-4-12 form of an IPD client key (SAH-28, SAH-30). */
class ClientKeysTest {

    private static final UUID KEY = UUID.randomUUID();

    @Test
    void aCanonicalKeyIsParsedInEitherCaseAndStripped() {
        assertThat(ClientKeys.parse(KEY.toString())).isEqualTo(KEY);
        assertThat(ClientKeys.parse(" " + KEY.toString().toUpperCase(Locale.ROOT) + " ")).isEqualTo(KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "1-1-1-1-1", "12345678-1234-1234-1234-12345678901",
            "12345678-1234-1234-1234-1234567890", "12345678123412341234123456789012",
            "1234567g-1234-1234-1234-123456789012", "１２345678-1234-1234-1234-123456789012",
            "+1234567-1234-1234-1234-123456789012", "12345678-1234-1234-1234-123456789012\n0"})
    void anythingElseIsNotAKey(String text) {
        assertThat(ClientKeys.parse(text)).isNull();
    }
}
