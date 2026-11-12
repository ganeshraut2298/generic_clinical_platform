package com.sahaj.platform.devtools.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.RawHttp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Shared helpers of the SAH-42 integration tests (ADR-018, docs/test-plans/SAH-42.md). Everything here is a throwaway: the
 * PIN, the password, the hash and the e-mail exist only in the test JVM and are never the human's. The PIN is deliberately not
 * six digits (it can never equal a real six-digit PIN, and it shows the server has no 6-digit rule, BR5).
 *
 * <p>The application is started like a developer starts it: the two settings come from the (simulated) operating-system
 * environment, because {@code SAHAJ_ADMIN_BOOTSTRAP_ENABLED} and {@code SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH} are read through the
 * Spring {@code Environment} (ADR-018 D2 B3, B4). Both are always set explicitly (an empty value is "off"), so a variable that
 * happens to exist in the machine's real environment can never change what a test means.
 *
 * <p>Nothing here prints the PIN, the hash or any ticket.
 */
final class BootstrapSupport {

    static final String UNLOCK = "/api/v1/admin/bootstrap/unlock";
    static final String REGISTER = "/api/v1/admin/bootstrap/register";
    static final String SAMPLE_PATH = "/api/v1/admin/dev/sample-admin";
    static final String ENABLED_ENV = "SAHAJ_ADMIN_BOOTSTRAP_ENABLED";
    static final String HASH_ENV = "SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH";
    static final String COOKIE = "sahaj_admin_bootstrap";

    /** Throwaway PIN of the tests. Not digits only. */
    static final String PIN = "qa-pin-7f3k";
    static final String EMAIL = "first.admin@example.test";
    static final String NAME = "First Admin";
    /** 20 characters, does not contain {@code first.admin}. */
    static final String PASSWORD = "qa-Throwaway-Pw-4821";

    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern TICKET_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");
    static final String INVALID_FORM_BAD_REQUEST_DETAIL = "The request could not be understood. Check it and try again.";

    private BootstrapSupport() {
    }

    /** BCrypt cost 12 hash of {@link #PIN}, made once (about a quarter of a second). */
    private static final class HashHolder {
        static final String VALUE = new BCryptPasswordEncoder(12).encode(PIN);
    }

    static String pinHash() {
        return HashHolder.VALUE;
    }

    /** All conditions B2 to B7 hold: dev,test profiles, loopback, realm on, switch on, valid hash. The sample-admin flag is off. */
    static AppRun.Builder builder() {
        return AppRun.builder().noFlag()
                .environmentVariable(ENABLED_ENV, "true")
                .environmentVariable(HASH_ENV, pinHash());
    }

    /**
     * Start (Flyway on, so the dev-tools callback runs) once if the bootstrap objects are not there yet, then require them.
     *
     * <p>Skip or fail (code review findings 2 and 3, ADR-018 Revision 3): only the three HUMAN prerequisites skip (role
     * {@code sahaj_admin_definer}, the admin tables, {@code admin_preauth_create}: the ordering guard and the owner's first sign-in, ADR-018 Revision 5); the canary
     * {@code BootstrapPrerequisitesCanaryIT} fails the build for them when {@code -Dsahaj.bootstrap.requireDb=true}. A stray admin row
     * fails. After the start attempt the objects MUST exist: a callback that did not create them is a failure, never a skip.
     */
    static void ensureObjectsOrSkip() {
        BootstrapDb.assumePrerequisites();
        BootstrapDb.failIfAnAdminExists();
        // Also upgrades a database that still holds the Revision 2 shapes (the callback drops them): not present until it ran.
        if (!BootstrapDb.objectsPresent() || BootstrapDb.oldShapePresent()) {
            try (AppRun app = builder().flywayOn().start()) {
                // The callback runs while the application starts. A failing callback is a failure of this test run.
                app.port();
            }
        }
        BootstrapDb.requireObjectsAfterStart();
        if (BootstrapDb.oldShapePresent()) {
            throw new AssertionError("A Revision 2 function shape (admin_bootstrap_begin(), unlock_failed(inet,text) or "
                    + "unlock_succeeded(text,text)) is still present after the callback ran: the callback must drop it (ADR-018 Revision 3).");
        }
    }

    /** The reference answer: the empty 404 of every dev-only path that is off (ADR-008 D3). */
    static String referenceNotFound(AppRun app) throws IOException {
        return app.request("GET", SAMPLE_PATH).send().assertDevNotFound().fingerprint();
    }

    // ---- requests ---------------------------------------------------------------------------------------------

    static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String pinBody(String pin) {
        return "{\"pin\":" + quote(pin) + "}";
    }

    static RawHttp.Request unlockRequest(AppRun app, String body) {
        return app.post(UNLOCK).json(body);
    }

    static RawHttp.Response unlock(AppRun app, String pin) throws IOException {
        return unlockRequest(app, pinBody(pin)).send();
    }

    static RawHttp.Request registerRequest(AppRun app, String body, String bindingCookie) {
        RawHttp.Request request = app.post(REGISTER).json(body);
        if (bindingCookie != null) {
            request.header("Cookie", COOKIE + "=" + bindingCookie);
        }
        return request;
    }

    static String registerBody(String ticket, String fullName, String email, String password) {
        return "{\"ticket\":" + quote(ticket) + ",\"fullName\":" + quote(fullName) + ",\"email\":" + quote(email)
                + ",\"password\":" + quote(password) + "}";
    }

    static RawHttp.Response register(AppRun app, Ticket ticket, String fullName, String email, String password)
            throws IOException {
        return registerRequest(app, registerBody(ticket.value(), fullName, email, password), ticket.binding()).send();
    }

    /** A correct unlock: the ticket from the body and the binding from the cookie. */
    record Ticket(String value, String binding) {
        @Override
        public String toString() {
            return "Ticket[]";
        }
    }

    static Ticket newTicket(AppRun app) throws IOException {
        RawHttp.Response response = unlock(app, PIN);
        if (response.status() != 200) {
            throw new AssertionError("The unlock with the throwaway PIN answered " + response.status());
        }
        return new Ticket(json(response).get("ticket").asText(), bindingOf(response));
    }

    // ---- reading answers --------------------------------------------------------------------------------------

    static JsonNode json(RawHttp.Response response) throws IOException {
        return JSON.readTree(response.body());
    }

    static JsonNode json(String text) throws IOException {
        return JSON.readTree(text);
    }

    static List<String> setCookies(RawHttp.Response response) {
        List<String> cookies = new ArrayList<>();
        for (String[] header : response.headers()) {
            if (header[0].equalsIgnoreCase("Set-Cookie")) {
                cookies.add(header[1]);
            }
        }
        return cookies;
    }

    /** The one Set-Cookie line of the binding cookie, or null. */
    static String bindingCookieLine(RawHttp.Response response) {
        return setCookies(response).stream().filter(c -> c.startsWith(COOKIE + "=")).findFirst().orElse(null);
    }

    static String bindingOf(RawHttp.Response response) {
        String line = bindingCookieLine(response);
        if (line == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("^" + COOKIE + "=([^;]*)").matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** What every answer of the two calls must carry, whatever the status (ADR-018 D2 "Responses"). */
    static String noStoreProblem(RawHttp.Response response) {
        StringBuilder problems = new StringBuilder();
        if (!"no-store".equals(response.header("Cache-Control"))) {
            problems.append("Cache-Control is not no-store; ");
        }
        if (!"no-cache".equals(response.header("Pragma"))) {
            problems.append("Pragma is not no-cache; ");
        }
        return problems.toString();
    }

    // ---- independent copy of the at-rest hash (ADR-018 D5) ---------------------------------------------------

    /** {@code HMAC-SHA256(k, value)} with {@code k = HMAC-SHA256(adminJwtSecret, "sahaj-admin-bootstrap-ticket-v1")}, as hex. */
    static String atRestHash(String value) {
        byte[] key = hmac(AppRun.ADMIN_JWT_SECRET.getBytes(StandardCharsets.UTF_8),
                "sahaj-admin-bootstrap-ticket-v1".getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hmac(key, value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Runs the real {@code BootstrapPinHashTool.run(...)} (package-private, so by reflection) with a fake hidden prompt that
     * "types" the PIN twice, and returns exactly what the tool prints to standard output, trimmed. This is the same code the
     * human's command runs; only the console is replaced (a test JVM has none). The tool's own refusals fail the call.
     */
    static String hashFromTool(String pin, int cost) throws Exception {
        Class<?> tool = Class.forName("com.sahaj.platform.devtools.bootstrap.BootstrapPinHashTool");
        Class<?> promptType = Class.forName("com.sahaj.platform.devtools.bootstrap.BootstrapPinHashTool$Prompt");
        Object prompt = java.lang.reflect.Proxy.newProxyInstance(promptType.getClassLoader(), new Class<?>[] {promptType},
                (proxy, method, args) -> method.getName().equals("readPassword") ? pin.toCharArray() : null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.lang.reflect.Method run = tool.getDeclaredMethod("run", promptType, java.io.PrintStream.class,
                java.io.PrintStream.class, int.class, java.security.SecureRandom.class);
        run.setAccessible(true);
        int status = (int) run.invoke(null, prompt, new java.io.PrintStream(out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8), cost, new java.security.SecureRandom());
        if (status != 0) {
            throw new AssertionError("The hash tool refused (exit " + status + "): " + err.toString(StandardCharsets.UTF_8));
        }
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    /** Every wrong PIN the tests use is different and obviously a throwaway. */
    static String wrongPin(int n) {
        return "qa-wrong-" + n + "-zq";
    }
}
