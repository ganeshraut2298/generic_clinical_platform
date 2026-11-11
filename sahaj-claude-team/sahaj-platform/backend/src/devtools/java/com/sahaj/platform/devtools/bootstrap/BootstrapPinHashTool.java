package com.sahaj.platform.devtools.bootstrap;

import java.io.Console;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Makes the BCrypt hash the human puts in {@code .env} as {@code SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH} (ADR-018 D3). The
 * PIN is typed at a hidden prompt, so it never appears in shell history, on screen, in a file or in a chat.
 * Exists only in a build made with {@code -Pdev-tools}.
 *
 * <p>How to run it, in PowerShell, from the repository root:
 *
 * <pre>
 * mvn -f backend/pom.xml -Pdev-tools -DskipTests package
 * java "-Dloader.main=com.sahaj.platform.devtools.bootstrap.BootstrapPinHashTool" -cp backend\target-devtools\sahaj-backend-devtools-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 *
 * <p>(The jar name carries the project version; use the file that {@code package} left in {@code backend\target-devtools}.)
 * The command line holds no PIN. The tool:
 *
 * <ol>
 *   <li>stops with "Run this in a terminal window (no console found)." when there is no console (an IDE run window), so
 *       it never falls back to echoing what is typed;
 *   <li>reads the PIN twice with {@code Console.readPassword}, which does not echo;
 *   <li>stops with one fixed sentence when the two differ, or the PIN is shorter than 6 or longer than 64 characters,
 *       or longer than 72 UTF-8 bytes;
 *   <li>prints <b>only</b> the hash (cost 14, for example {@code $2a$14$...}) on one line, zeroes its copies and exits.
 *       Nothing else is printed to standard output and nothing is written to disk.
 * </ol>
 *
 * <p>Paste the printed hash into {@code .env} in single quotes: {@code SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH='<hash>'}. It
 * contains {@code $}, which some {@code .env} loaders expand; if one mangles it the start-up line says the hash is not
 * valid. Never paste the PIN anywhere. Exit status: 0 for a hash, 1 for any refusal.
 */
public final class BootstrapPinHashTool {

    /** The cost the tool writes (about one second on a laptop). The server accepts 12 to 16. */
    public static final int COST = 14;
    static final int MIN_CHARACTERS = 6;
    static final int MAX_CHARACTERS = BootstrapPinVerifier.MAX_CHARACTERS;
    static final int MAX_BYTES = BootstrapPinVerifier.MAX_BYTES;

    static final String NO_CONSOLE = "Run this in a terminal window (no console found).";
    static final String MISMATCH = "The two entries are not the same. Nothing was made.";
    static final String BAD_LENGTH = "The PIN must be 6 to 64 characters and at most 72 bytes. Nothing was made.";
    static final String PROMPT_FIRST = "New registration PIN: ";
    static final String PROMPT_AGAIN = "Type it again: ";

    /** The two hidden prompts. {@link Console} is final, so the tool talks to this instead and the tests can fake it. */
    interface Prompt {
        char[] readPassword(String prompt);
    }

    private BootstrapPinHashTool() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        Prompt prompt = console == null ? null : console::readPassword;
        // The prompt text has no format specifiers; Console.readPassword(String, Object...) takes a format.
        System.exit(run(prompt, System.out, System.err, COST, new SecureRandom()));
    }

    /** @return the exit status; 0 only when a hash was printed to {@code out} */
    static int run(Prompt prompt, PrintStream out, PrintStream err, int cost, SecureRandom random) {
        if (prompt == null) {
            err.println(NO_CONSOLE);
            return 1;
        }
        char[] first = prompt.readPassword(PROMPT_FIRST);
        char[] second = prompt.readPassword(PROMPT_AGAIN);
        byte[] bytes = null;
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                err.println(MISMATCH);
                return 1;
            }
            int characters = Character.codePointCount(first, 0, first.length);
            if (characters < MIN_CHARACTERS || characters > MAX_CHARACTERS) {
                err.println(BAD_LENGTH);
                return 1;
            }
            bytes = utf8(first);
            if (bytes.length > MAX_BYTES) {
                err.println(BAD_LENGTH);
                return 1;
            }
            out.println(BCrypt.hashpw(bytes, BCrypt.gensalt(cost, random)));
            return 0;
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static byte[] utf8(char[] typed) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(typed));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }
}
