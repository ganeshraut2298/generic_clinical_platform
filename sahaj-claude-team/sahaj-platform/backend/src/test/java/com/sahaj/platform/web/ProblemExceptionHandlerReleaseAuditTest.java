package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Release audit G-12 and DEC-025 (the text for an unreadable value of the reload-the-page fields) and G-13 (no submitted value in the
 * logged first line of a database error).
 */
class ProblemExceptionHandlerReleaseAuditTest {

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();

    private ResponseEntity<Object> unreadable(String... path) {
        JsonMappingException cause = new JsonMappingException((java.io.Closeable) null, "cannot read");
        // prependPath adds at the front, so add from the innermost name out
        for (int i = path.length - 1; i >= 0; i--) {
            cause.prependPath(new JsonMappingException.Reference(Object.class, path[i]));
        }
        HttpInputMessage input = new MockHttpInputMessage(new byte[0]);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("unreadable", cause, input);
        return handler.handleHttpMessageNotReadable(ex, new HttpHeaders(), HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/platform/me/colleague-tries/seen")));
    }

    private static String messageOf(ResponseEntity<Object> response, String field) {
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        @SuppressWarnings("unchecked")
        List<Problems.FieldError> errors = (List<Problems.FieldError>) problem.getProperties().get("fieldErrors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).field()).isEqualTo(field);
        return errors.get(0).message();
    }

    @Test
    void anUnreadableSeenThroughAsksToReloadThePage() {
        ResponseEntity<Object> response = unreadable("seenThrough");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(messageOf(response, "seenThrough")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableClientKeyAsksToReloadThePage() {
        assertThat(messageOf(unreadable("clientKey"), "clientKey")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableIdentityUnknownAsksToReloadThePage() {
        assertThat(messageOf(unreadable("identityUnknown"), "identityUnknown"))
                .isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableVersionAsksToReloadThePage() {
        assertThat(messageOf(unreadable("version"), "version")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableSeniorCallsAsksToReloadThePage() {
        assertThat(messageOf(unreadable("seniorCalls"), "seniorCalls")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableSlotAtAsksToReloadThePage() {
        assertThat(messageOf(unreadable("slotAt"), "slotAt")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableCheckTokenAsksToReloadThePage() {
        assertThat(messageOf(unreadable("checkToken"), "checkToken")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void aNestedClientKeyIsDecidedByItsLastName() {
        assertThat(messageOf(unreadable("orders", "clientKey"), "orders.clientKey"))
                .isEqualTo("Reload the page and try again.");
    }

    @Test
    void anUnreadableOutcomeAsksToReloadThePage() {
        assertThat(messageOf(unreadable("outcome"), "outcome")).isEqualTo("Reload the page and try again.");
    }

    @Test
    void anyOtherUnreadableFieldKeepsChooseAValidOption() {
        assertThat(messageOf(unreadable("clientKeyboard"), "clientKeyboard")).isEqualTo("Choose a valid option");
        assertThat(messageOf(unreadable("orders", "unlistedField"), "orders.unlistedField"))
                .isEqualTo("Choose a valid option");
    }

    // ---- G-13 ----

    @Test
    void theQuotedValueOfAnInvalidInputSyntaxErrorIsCutOff() {
        SQLException error = new SQLException("ERROR: invalid input syntax for type uuid: \"Asha-Verma-9000000001\"\n  Where: x");

        String logged = ProblemExceptionHandler.primaryDbMessage(error);

        assertThat(logged).isEqualTo("ERROR: invalid input syntax for type uuid");
        assertThat(logged).doesNotContain("Asha").doesNotContain("9000000001");
    }

    @Test
    void anUnquotedValueAfterInvalidInputSyntaxIsCutOffToo() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(
                new SQLException("invalid input syntax for type integer: 9000000001")))
                .isEqualTo("invalid input syntax for type integer");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("Bad value for type int : 9000000001")))
                .isEqualTo("Bad value for type int");
    }

    @Test
    void aQuotedValueAfterAnyOtherWordIsCutOffAtItsQuote() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("value 'Asha' is too long")))
                .isEqualTo("value");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("value \"Asha\" is too long")))
                .isEqualTo("value");
        assertThat(ProblemExceptionHandler.primaryDbMessage(
                new SQLException("ERROR: invalid input value for enum status: \"Asha\"")))
                .isEqualTo("ERROR: invalid input value for enum status");
    }

    @Test
    void quotedIdentifiersAfterRelationColumnConstraintTableSchemaAndIndexAreKept() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(
                new SQLException("ERROR: duplicate key value violates unique constraint \"ux_mobile\"")))
                .isEqualTo("ERROR: duplicate key value violates unique constraint \"ux_mobile\"");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException(
                "ERROR: null value in column \"mobile\" of relation \"patient\" violates not-null constraint")))
                .isEqualTo("ERROR: null value in column \"mobile\" of relation \"patient\" violates not-null constraint");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("ERROR: schema \"ipd\" does not exist")))
                .isEqualTo("ERROR: schema \"ipd\" does not exist");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("ERROR: index \"ix_a\" is bad on table \"t\"")))
                .isEqualTo("ERROR: index \"ix_a\" is bad on table \"t\"");
    }

    @Test
    void anIdentifierIsKeptButAValueAfterItIsStillCut() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException(
                "ERROR: value too long for column \"name\" of type varchar: \"Asha Verma\"")))
                .isEqualTo("ERROR: value too long for column \"name\" of type varchar");
    }

    @Test
    void theDetailAndKeyLinesAreCutOffEvenOnTheFirstLine() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException(
                "ERROR: duplicate key value violates unique constraint \"ux_mobile\" Detail: Key (mobile)=(9000000001) exists.")))
                .isEqualTo("ERROR: duplicate key value violates unique constraint \"ux_mobile\"");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("ERROR: conflict Key (mobile)=(9000000001)")))
                .isEqualTo("ERROR: conflict");
    }

    @Test
    void anUnquotedValueAfterAColonInPgjdbcWordingsIsCutOff() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(
                new SQLException("Conversion to type java.util.UUID failed: Asha-Verma-9000000001")))
                .isEqualTo("Conversion to type java.util.UUID failed");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("Cannot cast to boolean: 9000000001")))
                .isEqualTo("Cannot cast to boolean");
        assertThat(ProblemExceptionHandler.primaryDbMessage(
                new SQLException("ERROR: Bad value for type long : Asha9000000001")))
                .isEqualTo("ERROR: Bad value for type long");
    }

    @Test
    void aPlainMessageWithoutAnyValueIsKeptAndOnlyItsFirstLineIsUsed() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(
                new SQLException("ERROR: deadlock detected\n  Detail: Process 1 waits")))
                .isEqualTo("ERROR: deadlock detected");
    }

    @Test
    void aNonSqlCauseOrAMissingMessageIsADash() {
        assertThat(ProblemExceptionHandler.primaryDbMessage(new IllegalStateException("x \"y\""))).isEqualTo("-");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException((String) null))).isEqualTo("-");
        assertThat(ProblemExceptionHandler.primaryDbMessage(new SQLException("\"only a value\""))).isEqualTo("-");
    }

    @Test
    void theUnhandledErrorLogLineHoldsNoSubmittedValue() {
        SQLException error = new SQLException("ERROR: invalid input syntax for type uuid: \"Asha-Verma-9000000001\"", "22P02");
        try (LogCapture logs = new LogCapture()) {
            handler.handleUnexpected(new RuntimeException("wrapped", error));

            List<ILoggingEvent> errors = logs.events().stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getFormattedMessage()).contains("sqlState=22P02")
                    .contains("dbError=ERROR: invalid input syntax for type uuid")
                    .doesNotContain("Asha").doesNotContain("9000000001");
            assertThat(logs.allText()).doesNotContain("9000000001");
        }
    }
}
