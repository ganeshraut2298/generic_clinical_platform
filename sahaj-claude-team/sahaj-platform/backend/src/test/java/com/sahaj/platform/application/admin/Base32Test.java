package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** RFC 4648 section 10 test vectors (padding removed on encode, accepted on decode). */
class Base32Test {

    private static final String[][] VECTORS = {
            {"", ""},
            {"f", "MY"},
            {"fo", "MZXQ"},
            {"foo", "MZXW6"},
            {"foob", "MZXW6YQ"},
            {"fooba", "MZXW6YTB"},
            {"foobar", "MZXW6YTBOI"},
    };

    @Test
    void encodesTheRfcVectors() {
        for (String[] vector : VECTORS) {
            assertThat(Base32.encode(vector[0].getBytes(StandardCharsets.US_ASCII))).isEqualTo(vector[1]);
        }
    }

    @Test
    void decodesTheRfcVectors() {
        for (String[] vector : VECTORS) {
            assertThat(new String(Base32.decode(vector[1]), StandardCharsets.US_ASCII)).isEqualTo(vector[0]);
        }
    }

    @Test
    void decodeAcceptsPaddingLowerCaseSpacesAndHyphens() {
        assertThat(new String(Base32.decode("MZXW6==="), StandardCharsets.US_ASCII)).isEqualTo("foo");
        assertThat(new String(Base32.decode("mzxw6ytboi"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(new String(Base32.decode("MZXW 6YTB-OI"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    }

    @Test
    void twentyBytesGiveThirtyTwoCharacters() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String encoded = Base32.encode(secret);
        assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").hasSize(32);
        assertThat(Base32.decode(encoded)).isEqualTo(secret);
    }

    @Test
    void roundTripsEveryByteValue() {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        assertThat(Base32.decode(Base32.encode(all))).isEqualTo(all);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabetWithoutEchoingThem() {
        assertThatThrownBy(() -> Base32.decode("MZXW1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("MZXW1");
    }

    @Test
    void groupsOfFour() {
        assertThat(Base32.inGroupsOfFour("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
                .isEqualTo("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ")
                .matches("^[A-Z2-7]{4}( [A-Z2-7]{4}){7}$");
    }
}
