package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.application.admin.Base32;
import com.sahaj.platform.application.admin.Totp;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * DEC-016 / ADR-008 tests T6, T7, T8, T9, T10, T11 (with a created admin), T15 and the 201 part of T16/T17: the endpoint on
 * the whole application with Flyway ON (so the dev-tools callback runs and creates the function), a real database and a real
 * socket.
 *
 * <p>PRECONDITIONS (a skipped test says which): the admin tables and the role {@code sahaj_admin_definer} exist (human
 * prerequisites 2 and 3 of ADR-008), and {@code platform.platform_admin} is empty. The endpoint is one-shot and the admin
 * tables are append-only, so each test starts its own application (the rate limit is 5 per context), takes a snapshot,
 * and removes what it created in {@code @AfterEach} through {@link SampleAdminDb#cleanup}.
 *
 * <p>These tests could not be run when they were written (the tables and the role do not exist yet): they were written
 * against the contract and the db design note and are the first thing to run once the prerequisites are done.
 */
class DevSampleAdminEndToEndIT {

    private static final Pattern PASSWORD = Pattern.compile("[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789]{24}");
    private static final Pattern GROUPED_KEY = Pattern.compile("([A-Z2-7]{4} ){7}[A-Z2-7]{4}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EMAIL = "sample-admin@example.test";

    private SampleAdminDb.Snapshot snapshot;
    private AppRun app;
    private String nonce;

    @BeforeEach
    void start() {
        SampleAdminDb.assumePrerequisites();
        SampleAdminDb.assumeNoAdmin();
        snapshot = SampleAdminDb.snapshot();
        app = AppRun.builder().flywayOn()
                .debug("root", "org.springframework.web", "org.springframework.web.servlet", "org.springframework.security",
                        "org.springframework.jdbc", "com.zaxxer.hikari")
                .start();
        nonce = app.nonceFromLog();
        assertThat(nonce).as("nonce in the start-up WARN line").matches("[0-9a-f]{32}");
        assertThat(SampleAdminDb.devFunctionPresent()).as("the dev-tools callback created the function at start").isTrue();
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.close();
        }
        if (snapshot != null) {
            SampleAdminDb.cleanup(snapshot);
        }
    }

    private JsonNode created() throws Exception {
        RawHttp.Response response = app.devCall(nonce).send();
        assertThat(response.status()).as("first call: " + response.bodyText().length() + " body chars").isEqualTo(201);
        return JSON.readTree(response.body());
    }

    private static byte[] secretOf(JsonNode body) {
        return Base32.decode(body.get("secretKey").asText().replace(" ", ""));
    }

    // ---- T6 ------------------------------------------------------------------------------------------------

    @Test
    void t6TheHappyPathReturnsTheContractBodyOnceAndTheSampleAdminCanSignInWithACodeFromTheReturnedSecret() throws Exception {
        RawHttp.Response response = app.devCall(nonce).send();

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(response.header("Pragma")).isEqualTo("no-cache");
        assertThat(response.header("Content-Type")).startsWith("application/json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(UUID.fromString(body.get("adminId").asText())).isNotNull();
        assertThat(body.get("email").asText()).isEqualTo(EMAIL);
        assertThat(body.get("fullName").asText()).isEqualTo("Sample admin");
        assertThat(body.get("password").asText()).matches(PASSWORD);
        assertThat(body.get("secretKey").asText()).matches(GROUPED_KEY);
        byte[] secret = secretOf(body);
        assertThat(secret).hasSize(20);
        String ungrouped = body.get("secretKey").asText().replace(" ", "");
        assertThat(body.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + ungrouped)
                .contains("digits=6").contains("period=30").contains("algorithm=SHA1");
        assertThat(body.get("accountName").asText()).isEqualTo(EMAIL);
        assertThat(body.get("issuer").asText()).isNotBlank();
        assertThat(body.get("digits").asInt()).isEqualTo(6);
        assertThat(body.get("periodSeconds").asInt()).isEqualTo(30);
        assertThat(body.get("algorithm").asText()).isEqualTo("SHA1");
        assertThat(body.get("totpEnrolled").asBoolean()).isTrue();
        assertThat(body.get("totpDevBypassOn").asBoolean()).as("bypass is off in this run").isFalse();
        assertThat(body.get("signInPath").asText()).isEqualTo("/admin/sign-in");

        // Sign in for real (the authenticator bypass is off): password first, then a code computed from the secret.
        RawHttp.Response login = app.post("/api/v1/admin/auth/login")
                .json("{\"email\":\"" + EMAIL + "\",\"password\":\"" + body.get("password").asText() + "\"}").send();
        assertThat(login.status()).as("login").isEqualTo(200);
        JsonNode loginBody = JSON.readTree(login.body());
        assertThat(loginBody.get("next").asText()).as("created enrolled, so the next step is the code").isEqualTo("TOTP");

        String code = Totp.generate(secret, Totp.stepAt(Instant.now()), Totp.DIGITS);
        RawHttp.Response verify = app.post("/api/v1/admin/auth/totp/verify")
                .json("{\"preAuthToken\":\"" + loginBody.get("preAuthToken").asText() + "\",\"code\":\"" + code + "\"}").send();
        assertThat(verify.status()).as("verify with a code from the returned secret").isEqualTo(200);
        assertThat(JSON.readTree(verify.body()).get("accessToken").asText()).isNotBlank();
    }

    @Test
    void t6ALocalhostHostSpellingWorksToo() throws Exception {
        // G4 allows 127.0.0.1, localhost and [::1] as Host. The raw client reaches the IPv4 listener only, so the Host
        // spelling is what can be varied end to end; the ::1 remote address is covered by DevSampleAdminGateTest.
        RawHttp.Response response = app.devCall(nonce).header("Host", "localhost:" + app.port()).send();
        assertThat(response.status()).isEqualTo(201);
    }

    // ---- T7 ------------------------------------------------------------------------------------------------

    @Test
    void t7StorageIsLikeTheNormalPathBcryptCost12EncryptedSecretBoundToTheAdminAndNoPlainSecretAnywhere() throws Exception {
        JsonNode body = created();
        UUID adminId = UUID.fromString(body.get("adminId").asText());
        String password = body.get("password").asText();
        byte[] secret = secretOf(body);
        TotpSecretCipher cipher = new TotpSecretCipher(Base64.getDecoder().decode(AppRun.ADMIN_TOTP_KEY_BASE64), new SecureRandom());

        try (Connection c = SampleAdminDb.migrator();
             PreparedStatement s = c.prepareStatement("select password_hash, totp_secret_enc, totp_key_version, "
                     + "totp_enrolled_at is not null, totp_last_used_step is null, active from platform.platform_admin where id = ?")) {
            s.setObject(1, adminId);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String hash = rs.getString(1);
                assertThat(hash).startsWith("$2a$12$").hasSize(60);
                assertThat(new BCryptPasswordEncoder().matches(password, hash)).as("hash matches the returned password").isTrue();
                byte[] stored = rs.getBytes(2);
                int version = rs.getInt(3);
                assertThat(cipher.decrypt(stored, adminId, version)).as("decrypts with this admin id").isEqualTo(secret);
                assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID(), version))
                        .as("fails with another admin id").isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
                assertThat(rs.getBoolean(4)).as("totp_enrolled_at set").isTrue();
                assertThat(rs.getBoolean(5)).as("totp_last_used_step null").isTrue();
                assertThat(rs.getBoolean(6)).as("active").isTrue();
            }
            assertThat(SampleAdminDb.scalarLong(c, "select count(*) from platform.platform_admin_recovery_code where admin_id = ?",
                    adminId)).as("no recovery codes").isZero();
            for (String table : List.of("platform_admin", "platform_admin_audit", "platform_admin_session",
                    "platform_admin_preauth", "platform_admin_recovery_code")) {
                try (PreparedStatement dump = c.prepareStatement("select coalesce(string_agg(row_to_json(t)::text, ' '), '') from platform."
                        + table + " t"); ResultSet rs = dump.executeQuery()) {
                    rs.next();
                    String everything = rs.getString(1);
                    assertThat(everything).as("table " + table + " holds no plain password, secret or URI")
                            .doesNotContain(password).doesNotContain(body.get("secretKey").asText())
                            .doesNotContain(body.get("secretKey").asText().replace(" ", ""))
                            .doesNotContain("otpauth://");
                }
            }
        }
    }

    // ---- T8 ------------------------------------------------------------------------------------------------

    @Test
    void t8ASecondCallNeverRevealsOrResetsAndLeavesExactlyOneRefusedAuditRow() throws Exception {
        created();
        String before = storedState();
        String refusalReference = app.devCall("f".repeat(32)).send().assertDevNotFound().fingerprint();

        RawHttp.Response second = app.devCall(nonce).send();

        second.assertDevNotFound();
        assertThat(second.fingerprint()).as("same bytes as any other 404").isEqualTo(refusalReference);
        assertThat(storedState()).as("password_hash, totp_secret_enc and updated_at unchanged").isEqualTo(before);
        List<String> audit = SampleAdminDb.auditRowsSince(snapshot);
        assertThat(audit.stream().filter(r -> r.contains("\"action\":\"ADMIN_DEV_SAMPLE_REFUSED\"")).count()).isEqualTo(1);
        assertThat(audit.stream().filter(r -> r.contains("\"action\":\"ADMIN_DEV_SAMPLE_CREATED\"")).count()).isEqualTo(1);
    }

    private static final String SEEDED_STATE =
            "select md5(password_hash || coalesce(updated_at::text, '') || active::text) from platform.platform_admin";

    private static String ownerText(String sql) throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarText(c, sql);
        }
    }

    private String storedState() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarText(c, "select md5(password_hash || encode(totp_secret_enc, 'hex') || coalesce(updated_at::text, '')) "
                    + "from platform.platform_admin where email = '" + EMAIL + "'");
        }
    }

    // ---- T9 ------------------------------------------------------------------------------------------------

    @Test
    void t9AnAdminThatAlreadyExistsActiveOrInactiveClosesTheEndpointAndIsLeftUnchanged() throws Exception {
        for (boolean active : new boolean[] {true, false}) {
            SampleAdminDb.Snapshot own = SampleAdminDb.snapshot();
            try {
                SampleAdminDb.insertAdmin("seed-" + active + "@example.test", active);
                String before = ownerText(SEEDED_STATE);

                app.devCall(nonce).send().assertDevNotFound();

                assertThat(SampleAdminDb.adminCount()).as("active=" + active + ": nothing inserted").isEqualTo(1);
                assertThat(ownerText(SEEDED_STATE))
                        .as("the seeded admin is unchanged").isEqualTo(before);
            } finally {
                SampleAdminDb.cleanup(own);
            }
        }
    }

    // ---- T10 -----------------------------------------------------------------------------------------------

    @Test
    void t10TenParallelCallsCreateExactlyOneAdmin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return app.devCall(nonce).send().status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(120, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(s -> s == 201).count()).as("created: " + statuses).isEqualTo(1);
            // ADR-008 T10 says "nine 404"; the rate limit (5 calls per 15 minutes, D6) turns the calls after the fifth
            // into 429 even though they pass the gate, so the others are 404 or 429, never 201 or 5xx.
            assertThat(statuses.stream().filter(s -> s != 201)).as("the other nine: " + statuses).allMatch(s -> s == 404 || s == 429);
            assertThat(SampleAdminDb.adminCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- T11 / T17 with a created admin --------------------------------------------------------------------

    @Test
    void t11NeitherTheLogsAtDebugNorTheAuditRowsHoldAnySecretOfTheFirstOrSecondCallAndTheNonceIsInItsLineOnly() throws Exception {
        JsonNode body = created();
        app.devCall(nonce).send().assertDevNotFound();

        String hash;
        byte[] stored;
        try (Connection c = SampleAdminDb.migrator(); PreparedStatement s = c.prepareStatement(
                "select password_hash, totp_secret_enc from platform.platform_admin where email = ?")) {
            s.setString(1, EMAIL);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                hash = rs.getString(1);
                stored = rs.getBytes(2);
            }
        }
        String grouped = body.get("secretKey").asText();
        List<String> forbidden = List.of(body.get("password").asText(), grouped, grouped.replace(" ", ""),
                body.get("otpauthUri").asText(), "secret=", EMAIL, hash, HexFormat.of().formatHex(stored),
                Base64.getEncoder().encodeToString(stored), "Failing row");
        String logs = app.logText();
        for (String value : forbidden) {
            assertThat(logs).as("logs at DEBUG must not contain a secret value").doesNotContain(value);
        }
        assertThat(logs.split(nonce, -1).length - 1).as("occurrences of the nonce in the logs").isEqualTo(1);
        for (String row : SampleAdminDb.auditRowsSince(snapshot)) {
            for (String value : forbidden) {
                assertThat(row).as("audit row column text").doesNotContain(value);
            }
            assertThat(row).doesNotContain(nonce);
        }
    }

    // ---- T15 -----------------------------------------------------------------------------------------------

    @Test
    void t15ATokenAndACookieSentWithAPassingRequestAreIgnored() throws Exception {
        RawHttp.Response response = app.devCall(nonce)
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.junk")
                .header("Cookie", "sahaj_admin_rt=junk; sahaj_rt=junk").send();
        assertThat(response.status()).as("same result as without tokens").isEqualTo(201);
    }
}
