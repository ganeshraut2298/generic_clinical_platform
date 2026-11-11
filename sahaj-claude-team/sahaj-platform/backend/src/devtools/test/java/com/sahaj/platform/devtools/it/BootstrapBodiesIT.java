package com.sahaj.platform.devtools.it;

import static com.sahaj.platform.devtools.it.BootstrapSupport.INVALID_FORM_BAD_REQUEST_DETAIL;
import static com.sahaj.platform.devtools.it.BootstrapSupport.NAME;
import static com.sahaj.platform.devtools.it.BootstrapSupport.PASSWORD;
import static com.sahaj.platform.devtools.it.BootstrapSupport.json;
import static com.sahaj.platform.devtools.it.BootstrapSupport.newTicket;
import static com.sahaj.platform.devtools.it.BootstrapSupport.quote;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.devtools.it.BootstrapSupport.Ticket;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.RawHttp;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SAH-42 ADR-018 D2 "Request bodies" (Revision 2): every row of the table, on both endpoints, on the whole application with the
 * real bootstrap functions in {@code sahaj_test}. Two kinds of 400, never mixed: an unreadable body is {@code 400 bad-request}
 * (fixed text, no field errors); a readable body with a missing, null or blank field is {@code 400 validation}. Neither is
 * counted, audited or spends the ticket, and no 400 echoes a submitted value or field name. The order of answers is closed (404),
 * locked (423), ticket (401, register only), body (400), then the PIN or the form.
 *
 * <p>Same preconditions and skip behaviour as {@link BootstrapUnlockIT}.
 */
class BootstrapBodiesIT extends BootstrapHttpBase {

    private static final String PROBLEM = "https://sahaj.in/problems/";

    /** Bodies the strict reader cannot use (ADR-018 D2). The map value says why. */
    private static Map<String, String> unreadableUnlockBodies() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("", "empty body");
        bodies.put("not json", "not JSON");
        bodies.put("{", "truncated JSON");
        bodies.put("[]", "an array");
        bodies.put("[\"a\"]", "an array with a value");
        bodies.put("\"text\"", "a string");
        bodies.put("123", "a number");
        bodies.put("true", "a boolean");
        bodies.put("null", "JSON null as the whole body");
        bodies.put("{\"pin\":\"x\",\"pin\":\"y\"}", "a repeated key");
        bodies.put("{\"pin\":\"x\"} {}", "something after the object");
        bodies.put("{\"pin\":\"x\"}x", "text after the object");
        bodies.put("{\"pin\":\"x\",\"secretFieldName\":\"zzValue\"}", "an unknown field");
        bodies.put("{\"secretFieldName\":\"zzValue\"}", "only an unknown field");
        bodies.put("{\"PIN\":\"x\"}", "the field name in capitals");
        bodies.put("{\"pin\":123}", "a number as the PIN");
        bodies.put("{\"pin\":true}", "a boolean as the PIN");
        bodies.put("{\"pin\":[\"a\"]}", "an array as the PIN");
        bodies.put("{\"pin\":{\"a\":1}}", "an object as the PIN");
        return bodies;
    }

    private void assertBadRequest(RawHttp.Response response, String label) throws IOException {
        assertThat(response.status()).as(label).isEqualTo(400);
        assertThat(response.header("Content-Type")).as(label).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).as(label).isEqualTo(PROBLEM + "bad-request");
        assertThat(body.get("title").asText()).as(label).isEqualTo("Bad Request");
        assertThat(body.get("detail").asText()).as(label).isEqualTo(INVALID_FORM_BAD_REQUEST_DETAIL);
        assertThat(body.has("fieldErrors")).as(label + ": an unreadable body has no field errors").isFalse();
        assertThat(BootstrapSupport.noStoreProblem(response)).as(label).isEmpty();
        assertThat(BootstrapSupport.setCookies(response)).as(label + ": no cookie").isEmpty();
        assertThat(response.bodyText()).as(label + ": nothing of the request is echoed")
                .doesNotContain("secretFieldName").doesNotContain("zzValue");
    }

    private void assertPinBlank(RawHttp.Response response, String label) throws IOException {
        assertThat(response.status()).as(label).isEqualTo(400);
        JsonNode body = json(response);
        assertThat(body.get("type").asText()).as(label).isEqualTo(PROBLEM + "validation");
        JsonNode errors = body.get("fieldErrors");
        assertThat(errors.size()).as(label + ": exactly one field error").isEqualTo(1);
        assertThat(errors.get(0).get("field").asText()).isEqualTo("pin");
        assertThat(errors.get(0).get("message").asText()).isEqualTo("Enter the PIN.");
        assertThat(BootstrapSupport.noStoreProblem(response)).isEmpty();
        assertThat(BootstrapSupport.setCookies(response)).isEmpty();
    }

    // ---- unlock --------------------------------------------------------------------------------------------

    @Test
    void unlockAnUnreadableBodyIs400BadRequestWithFixedTextAndIsNeverCountedOrAudited() throws Exception {
        for (Map.Entry<String, String> entry : unreadableUnlockBodies().entrySet()) {
            RawHttp.Response response = BootstrapSupport.unlockRequest(app, entry.getKey()).send();
            assertBadRequest(response, "unlock, " + entry.getValue());
            assertThat(BootstrapDb.state().failures()).as(entry.getValue() + ": not counted").isZero();
            assertThat(BootstrapDb.state().hasTicket()).as(entry.getValue() + ": no ticket").isFalse();
        }
        assertThat(BootstrapDb.auditActions(snapshot)).as("never audited").isEmpty();
    }

    @Test
    void unlockAMissingNullEmptyOrBlankPinIs400ValidationWithOneFieldErrorAndIsNeverCounted() throws Exception {
        for (String body : new String[] {"{}", "{\"pin\":null}", "{\"pin\":\"\"}", "{\"pin\":\"   \"}", "{\"pin\":\"\\t\"}",
                "{\"pin\":\"\\n \\r\"}"}) {
            assertPinBlank(BootstrapSupport.unlockRequest(app, body).send(), "unlock " + body);
            assertThat(BootstrapDb.state().failures()).as(body + ": a blank PIN is not counted (BR7f)").isZero();
        }
        assertThat(BootstrapDb.auditActions(snapshot)).as("a blank PIN is not audited (BR12)").isEmpty();
    }

    @Test
    void unlockAnyOtherStringIsJudgedAsAPinAndCountedWhenWrongWithoutTrimming() throws Exception {
        RawHttp.Response untrimmed = BootstrapSupport.unlockRequest(app, BootstrapSupport.pinBody(" " + BootstrapSupport.PIN + " ")).send();
        assertThat(untrimmed.status()).as("the PIN is compared exactly as sent").isEqualTo(401);
        assertThat(BootstrapDb.state().failures()).isEqualTo(1);
        RawHttp.Response one = BootstrapSupport.unlockRequest(app, "{\"pin\":\"0\"}").send();
        assertThat(one.status()).isEqualTo(401);
        assertThat(BootstrapDb.state().failures()).isEqualTo(2);
    }

    @Test
    void unlockWhileLockedTheLockAnswersBeforeTheBodyIsLookedAt() throws Exception {
        BootstrapDb.lockFor(Duration.ofMinutes(20));
        for (String body : new String[] {"not json", "{\"pin\":123}", "{}", "{\"pin\":\"\"}", "{\"x\":1}"}) {
            RawHttp.Response response = BootstrapSupport.unlockRequest(app, body).send();
            assertThat(response.status()).as("locked beats the body: " + body).isEqualTo(423);
            assertThat(json(response).get("type").asText()).isEqualTo(PROBLEM + "bootstrap-locked");
        }
        assertThat(BootstrapDb.state().failures()).isEqualTo(5);
    }

    // ---- register --------------------------------------------------------------------------------------------

    private RawHttp.Response register(String body, Ticket ticket) throws IOException {
        return BootstrapSupport.registerRequest(app, body, ticket == null ? null : ticket.binding()).send();
    }

    @Test
    void registerWithoutAReadableValidTicketTheAnswerIsAlwaysThe401EvenForABrokenBody() throws Exception {
        Ticket ticket = newTicket(app);
        String t = quote(ticket.value());
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("empty body", "");
        bodies.put("not JSON", "not json");
        bodies.put("an array", "[]");
        bodies.put("truncated", "{\"ticket\":" + t);
        bodies.put("a repeated key", "{\"ticket\":" + t + ",\"ticket\":" + t + "}");
        bodies.put("something after the object", "{\"ticket\":" + t + "} {}");
        bodies.put("a non-string ticket", "{\"ticket\":123}");
        bodies.put("an unknown field and no ticket", "{\"secretFieldName\":\"zzValue\"}");
        bodies.put("no ticket at all", "{}");
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            RawHttp.Response response = register(entry.getValue(), ticket);
            assertThat(response.status()).as(entry.getKey() + ": the ticket check comes first").isEqualTo(401);
            assertThat(json(response).get("type").asText()).isEqualTo(PROBLEM + "bootstrap-ticket-invalid");
            assertThat(response.bodyText()).doesNotContain("secretFieldName").doesNotContain("zzValue");
        }
        assertThat(BootstrapDb.state().hasTicket()).as("the open ticket survives all of that").isTrue();
    }

    @Test
    void registerAValidTicketAndAnUnreadableRestOfTheBodyIs400BadRequestAndTheTicketIsNotSpent() throws Exception {
        Ticket ticket = newTicket(app);
        String t = quote(ticket.value());
        BootstrapDb.State before = BootstrapDb.state();
        List<String> bodies = List.of(
                "{\"ticket\":" + t + ",\"fullName\":\"A\",\"email\":\"a@example.test\",\"password\":\"x\",\"secretFieldName\":\"zzValue\"}",
                "{\"ticket\":" + t + ",\"secretFieldName\":null}",
                "{\"ticket\":" + t + ",\"password\":123}",
                "{\"ticket\":" + t + ",\"fullName\":true}",
                "{\"ticket\":" + t + ",\"email\":[\"a\"]}",
                "{\"ticket\":" + t + ",\"fullName\":{\"a\":1}}",
                "{\"ticket\":" + t + ",\"Email\":\"a@example.test\"}");
        for (String body : bodies) {
            assertBadRequest(register(body, ticket), "register " + body.replace(ticket.value(), "T"));
            assertThat(BootstrapDb.state()).as("the ticket is not spent").isEqualTo(before);
        }
        assertThat(BootstrapDb.auditActions(snapshot)).as("never audited").isEmpty();
    }

    @Test
    void registerAValidTicketAndAMissingNullOrBlankFieldIs400ValidationWithMissingAndNullTreatedAsEmpty() throws Exception {
        Ticket ticket = newTicket(app);
        String t = quote(ticket.value());
        BootstrapDb.State before = BootstrapDb.state();
        List<String> bodies = List.of(
                "{\"ticket\":" + t + "}",
                "{\"ticket\":" + t + ",\"fullName\":null,\"email\":null,\"password\":null}",
                "{\"ticket\":" + t + ",\"fullName\":\"\",\"email\":\"\",\"password\":\"\"}",
                "{\"ticket\":" + t + ",\"fullName\":\"   \",\"email\":\"   \",\"password\":\"   \"}");
        for (String body : bodies) {
            RawHttp.Response response = register(body, ticket);
            assertThat(response.status()).as(body.replace(ticket.value(), "T")).isEqualTo(400);
            JsonNode problem = json(response);
            assertThat(problem.get("type").asText()).isEqualTo(PROBLEM + "validation");
            JsonNode errors = problem.get("fieldErrors");
            assertThat(errors.size()).as("at most two field errors, by design").isEqualTo(2);
            assertThat(errors.get(0).get("field").asText()).isEqualTo("fullName");
            assertThat(errors.get(0).get("message").asText()).isEqualTo("Enter the full name.");
            assertThat(errors.get(1).get("field").asText()).isEqualTo("email");
            assertThat(errors.get(1).get("message").asText()).isEqualTo("Enter a valid email.");
            assertThat(BootstrapDb.state()).as("the ticket is not spent").isEqualTo(before);
        }
        // The same ticket then creates the admin.
        assertThat(BootstrapSupport.register(app, ticket, NAME, uniqueEmail(), PASSWORD).status()).isEqualTo(201);
    }

    @Test
    void r3I9AJsonEscapedNulInTheNameOrTheEmailIsAReadableBodyThatFailsValidationNotABadRequestAndNotAFiveHundred() throws Exception {
        // ADR-018 D6 (Revision 3): the body is well-formed JSON, so this is the 400 validation of D2, never bad-request or 500.
        Ticket ticket = newTicket(app);
        String t = quote(ticket.value());
        BootstrapDb.State before = BootstrapDb.state();
        String nul = "\\u0000"; // backslash, u, four digits: the JSON escape, not a character of this source file
        List<String> bodies = List.of(
                "{\"ticket\":" + t + ",\"fullName\":\"A" + nul + "B\",\"email\":\"a@example.test\",\"password\":\"" + PASSWORD + "\"}",
                "{\"ticket\":" + t + ",\"fullName\":\"AB\",\"email\":\"a" + nul + "@example.test\",\"password\":\"" + PASSWORD + "\"}");
        for (String body : bodies) {
            RawHttp.Response response = register(body, ticket);
            assertThat(response.status()).as("readable body, bad value").isEqualTo(400);
            JsonNode problem = json(response);
            assertThat(problem.get("type").asText()).as("validation, not bad-request").isEqualTo(PROBLEM + "validation");
            assertThat(problem.get("fieldErrors").size()).isEqualTo(1);
            assertThat(BootstrapDb.state()).as("the ticket is not spent").isEqualTo(before);
        }
        assertThat(BootstrapDb.auditActions(snapshot)).isEmpty();
    }

    @Test
    void registerWhileLockedTheLockAnswersBeforeTheBodyAndWithoutATicket() throws Exception {
        BootstrapDb.lockFor(Duration.ofMinutes(15));
        for (String body : new String[] {"not json", "{\"secretFieldName\":1}", "{}"}) {
            RawHttp.Response response = register(body, null);
            assertThat(response.status()).as("locked beats ticket and body: " + body).isEqualTo(423);
            assertThat(json(response).get("retryAfterSeconds").asLong()).isBetween(800L, 900L);
        }
    }
}
