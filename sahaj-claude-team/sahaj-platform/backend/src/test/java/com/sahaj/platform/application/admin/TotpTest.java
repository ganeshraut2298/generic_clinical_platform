package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** RFC 4226 Appendix D and RFC 6238 Appendix B (SHA-1) test vectors. */
class TotpTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void hotpMatchesRfc4226AppendixD() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583",
                "399871", "520489"};
        for (int counter = 0; counter < expected.length; counter++) {
            assertThat(Totp.generate(SECRET, counter, 6)).isEqualTo(expected[counter]);
        }
    }

    @Test
    void totpMatchesRfc6238AppendixBWithEightDigits() {
        long[][] times = {{59L}, {1111111109L}, {1111111111L}, {1234567890L}, {2000000000L}, {20000000000L}};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < times.length; i++) {
            long step = Totp.stepAt(Instant.ofEpochSecond(times[i][0]));
            assertThat(Totp.generate(SECRET, step, 8)).isEqualTo(expected[i]);
        }
    }

    @Test
    void sixDigitCodeIsTheLastSixDigitsOfTheEightDigitValue() {
        long step = Totp.stepAt(Instant.ofEpochSecond(1111111109L));
        assertThat(Totp.generate(SECRET, step, 6)).isEqualTo("081804");
    }

    @Test
    void matchStepAcceptsTheCurrentStepAndOneEitherSide() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long current = Totp.stepAt(now);
        for (long step = current - 1; step <= current + 1; step++) {
            assertThat(Totp.matchStep(SECRET, Totp.generate(SECRET, step, 6), now, null)).isEqualTo(step);
        }
    }

    @Test
    void matchStepRefusesTwoStepsAway() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long current = Totp.stepAt(now);
        assertThat(Totp.matchStep(SECRET, Totp.generate(SECRET, current - 2, 6), now, null)).isNull();
        assertThat(Totp.matchStep(SECRET, Totp.generate(SECRET, current + 2, 6), now, null)).isNull();
    }

    @Test
    void matchStepRefusesAStepNotLaterThanTheLastUsedOne() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long current = Totp.stepAt(now);
        String code = Totp.generate(SECRET, current, 6);
        assertThat(Totp.matchStep(SECRET, code, now, current)).isNull();
        assertThat(Totp.matchStep(SECRET, code, now, current + 1)).isNull();
        assertThat(Totp.matchStep(SECRET, code, now, current - 1)).isEqualTo(current);
    }

    @Test
    void matchStepRefusesMalformedCodes() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        assertThat(Totp.matchStep(SECRET, null, now, null)).isNull();
        assertThat(Totp.matchStep(SECRET, "12345", now, null)).isNull();
        assertThat(Totp.matchStep(SECRET, "1234567", now, null)).isNull();
        assertThat(Totp.matchStep(SECRET, "12a456", now, null)).isNull();
        assertThat(Totp.matchStep(SECRET, "", now, null)).isNull();
    }

    @Test
    void aWrongSixDigitCodeIsRefused() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long current = Totp.stepAt(now);
        String valid = Totp.generate(SECRET, current, 6);
        String wrong = valid.equals("000000") ? "000001" : "000000";
        assertThat(Totp.matchStep(SECRET, wrong, now, null)).isNull();
    }
}
