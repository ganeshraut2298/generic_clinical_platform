package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** ADR-018 D3: the hidden-prompt tool prints only the hash, and refuses with fixed sentences. */
class BootstrapPinHashToolTest {

    /** Throwaway PINs only. */
    private static final String PIN = "135790";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> prompts = new ArrayList<>();

    private BootstrapPinHashTool.Prompt typing(String... entries) {
        Deque<String> queue = new ArrayDeque<>(List.of(entries));
        return prompt -> {
            prompts.add(prompt);
            String next = queue.poll();
            return next == null ? null : next.toCharArray();
        };
    }

    private int run(BootstrapPinHashTool.Prompt prompt, int cost) {
        return BootstrapPinHashTool.run(prompt, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), cost, new SecureRandom());
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void twoMatchingEntriesPrintOnlyAValidHashThatChecksAgainstThePin() {
        int status = run(typing(PIN, PIN), 12);

        assertThat(status).isZero();
        assertThat(stdout()).matches("(?s)\\$2a\\$12\\$[./A-Za-z0-9]{53}\\R");
        assertThat(stderr()).isEmpty();
        assertThat(BootstrapConditionsHashCheck.valid(stdout().trim())).isTrue();
        assertThat(BCrypt.checkpw(PIN.getBytes(StandardCharsets.UTF_8), stdout().trim())).isTrue();
        assertThat(BCrypt.checkpw("135791".getBytes(StandardCharsets.UTF_8), stdout().trim())).isFalse();
        assertThat(stdout()).doesNotContain(PIN);
        assertThat(prompts).containsExactly("New registration PIN: ", "Type it again: ");
    }

    @Test
    void theDefaultCostIsFourteenAndTheServerAcceptsIt() {
        assertThat(BootstrapPinHashTool.COST).isEqualTo(14);
        int status = run(typing(PIN, PIN), BootstrapPinHashTool.COST);

        assertThat(status).isZero();
        assertThat(stdout()).startsWith("$2a$14$");
        assertThat(BootstrapConditionsHashCheck.valid(stdout().trim())).isTrue();
    }

    @Test
    void theHashItMakesIsAcceptedByTheVerifier() {
        run(typing(PIN, PIN), 12);

        BootstrapPinVerifier verifier = new BootstrapPinVerifier(stdout().trim(), new SecureRandom());
        assertThat(verifier.matches(PIN.toCharArray())).isTrue();
        assertThat(verifier.matches("135791".toCharArray())).isFalse();
    }

    @Test
    void noConsoleStopsWithTheFixedSentenceAndNeverPrompts() {
        int status = run(null, 12);

        assertThat(status).isEqualTo(1);
        assertThat(stderr().trim()).isEqualTo("Run this in a terminal window (no console found).");
        assertThat(stdout()).isEmpty();
        assertThat(prompts).isEmpty();
    }

    @Test
    void entriesThatDifferPrintNoHashAndNoPin() {
        int status = run(typing(PIN, "135791"), 12);

        assertThat(status).isEqualTo(1);
        assertThat(stdout()).isEmpty();
        assertThat(stderr().trim()).isEqualTo("The two entries are not the same. Nothing was made.");
        assertThat(stderr()).doesNotContain(PIN).doesNotContain("135791");
    }

    @Test
    void aMissingEntryIsTreatedAsADifference() {
        assertThat(run(typing(PIN), 12)).isEqualTo(1);
        assertThat(stdout()).isEmpty();
    }

    @Test
    void aPinThatIsTooShortOrTooLongIsRefusedWithOneFixedSentence() {
        String tooLongBytes = "€".repeat(25);
        String tooLongChars = "9".repeat(65);
        for (String bad : new String[] {"", "1", "12345", tooLongChars, tooLongBytes}) {
            out.reset();
            err.reset();
            int status = run(typing(bad, bad), 12);

            assertThat(status).as("length " + bad.length()).isEqualTo(1);
            assertThat(stdout()).isEmpty();
            assertThat(stderr().trim()).isEqualTo(
                    "The PIN must be 6 to 64 characters and at most 72 bytes. Nothing was made.");
        }
    }

    @Test
    void sixAndSixtyFourCharactersAndSeventyTwoBytesAreAccepted() {
        for (String ok : new String[] {"123456", "7".repeat(64), "€".repeat(24)}) {
            out.reset();
            err.reset();
            assertThat(run(typing(ok, ok), 12)).as("length " + ok.length()).isZero();
            assertThat(stdout().trim()).startsWith("$2a$12$");
        }
    }

    @Test
    void nothingButTheHashGoesToStandardOutput() {
        run(typing(PIN, PIN), 12);

        assertThat(stdout().trim().lines().count()).isEqualTo(1);
        assertThat(stdout().trim()).hasSize(60);
    }

    /** The server's own B4 test, reused so the tool and the start-up condition cannot drift apart. */
    private static final class BootstrapConditionsHashCheck {
        static boolean valid(String hash) {
            return BootstrapConditions.PIN_HASH_FORMAT.matcher(hash).matches();
        }
    }
}
