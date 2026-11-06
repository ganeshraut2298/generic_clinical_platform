package com.sahaj.platform.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place integration tests get a brand new mobile number from.
 *
 * <p>Why it exists: the test database {@code sahaj_test} is never cleaned, so every earlier run leaves its
 * users and memberships behind. A mobile drawn at random from a 10-digit space (the old
 * {@code "9" + 9 random digits}, 10^9 values) therefore had a real chance of already belonging to a user with a
 * clinic, which turns a "fresh sign-up" into a login (CHOOSE_CLINIC, or a clinic whose setup is already done) and
 * fails an unrelated test. With thousands of draws per run against tens of thousands of leftover rows that is a
 * collision every few runs, and it also made two draws inside one run possible.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Never the same value twice in one JVM: a per-JVM random start plus an {@link AtomicLong} counter walks
 *       the block with a step that is coprime to the block size, so the sequence cannot repeat before the
 *       whole block is used (7 x 10^8 values).</li>
 *   <li>Own block: 7200000000 to 7899999999. The old generator used the 9 prefix and so did most fixed
 *       constants in the tests (9876543210, 9123456780, 9000000001 and so on), and the format checks use
 *       6000000000, 7000000000, 7123456789, 7999999999, 8xxxxxxxxx. None of them is inside the block, so a
 *       fixed constant can never equal a generated mobile, and leftovers from before this helper cannot either.</li>
 *   <li>Leftovers from earlier runs of this helper: each candidate is checked against
 *       {@code platform.app_user} and {@code platform.otp_challenge} on sahaj_test (one cheap query each) and
 *       skipped if present. If the database cannot be reached from the helper the check is skipped, the
 *       in-run guarantee still holds, and a line is written to stderr.</li>
 *   <li>Thread safe (some ITs sign people up from several threads).</li>
 * </ul>
 * A mobile still matches {@code ^[6-9][0-9]{9}$}. A test that needs a fixed mobile must keep it outside the block.
 */
final class TestMobiles {

    private static final long BLOCK_START = 7_200_000_000L;
    private static final long BLOCK_SIZE = 700_000_000L;
    /** Prime and not a factor of 7 * 2^8 * 5^8 (= BLOCK_SIZE), so stepping by it visits every value once. */
    private static final long STEP = 1_000_003L;

    private static final long RUN_OFFSET = ThreadLocalRandom.current().nextLong(BLOCK_SIZE);
    private static final AtomicLong COUNTER = new AtomicLong();

    private static Connection connection;
    private static boolean checkDisabled;

    private TestMobiles() {
    }

    /** A mobile that no earlier call in this JVM returned and that no user or one-time code in sahaj_test uses. */
    static String next() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            long index = COUNTER.getAndIncrement();
            long value = BLOCK_START + Math.floorMod(RUN_OFFSET + index * STEP, BLOCK_SIZE);
            String mobile = Long.toString(value);
            if (!alreadyInDatabase(mobile)) {
                return mobile;
            }
        }
        throw new IllegalStateException("Could not find an unused test mobile after 1000 candidates");
    }

    private static synchronized boolean alreadyInDatabase(String mobile) {
        if (checkDisabled) {
            return false;
        }
        try {
            if (connection == null || connection.isClosed()) {
                connection = open();
            }
            return exists("select 1 from platform.app_user where mobile = ?", mobile)
                    || exists("select 1 from platform.otp_challenge where mobile = ?", mobile);
        } catch (SQLException | RuntimeException e) {
            checkDisabled = true;
            System.err.println("TestMobiles: database pre-check is off (" + e.getClass().getSimpleName()
                    + "); in-run uniqueness still holds");
            return false;
        }
    }

    private static Connection open() throws SQLException {
        String url = System.getenv().getOrDefault("SAHAJ_TEST_DB_URL", "jdbc:postgresql://localhost:5432/sahaj_test");
        String password = System.getenv("SAHAJ_DB_APP_PASSWORD");
        if (password == null) {
            throw new IllegalStateException("SAHAJ_DB_APP_PASSWORD is not set");
        }
        Connection opened = DriverManager.getConnection(url, "sahaj_app", password);
        opened.setAutoCommit(true);
        opened.setReadOnly(true);
        return opened;
    }

    private static boolean exists(String sql, String mobile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mobile);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
