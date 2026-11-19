package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sahaj.pharmacy.application.IntegrationOffException;
import com.sahaj.pharmacy.application.PharmacyInputs;
import com.sahaj.pharmacy.application.PharmacyInputs.Body;
import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.domain.IntegrationMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The body reader of the pharmacy controllers ({@code PharmacyBodies}) and the problems the handler answers for it. The
 * mapper is set up as the application's is: Java time, the closed-body module, the present-or-absent module, and unknown
 * fields otherwise ignored.
 */
class PharmacyBodiesTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new PharmacySentJsonModule()).registerModule(new PharmacyStrictJsonModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    @SuppressWarnings("unchecked")
    private final PharmacyProblemHandler handler = new PharmacyProblemHandler(mock(ObjectProvider.class));

    private <T> ResponseEntity<ProblemDetail> problemOf(String raw, Class<T> type) {
        Body<T> body = PharmacyBodies.of(mapper, raw, type);
        try {
            body.read();
        } catch (UnreadableBodyException unreadable) {
            return handler.handleUnreadableBody(unreadable);
        }
        throw new AssertionError("the body should not have been readable");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> firstFieldError(ResponseEntity<ProblemDetail> response) {
        List<Map<String, String>> errors = (List<Map<String, String>>) response.getBody().getProperties().get("fieldErrors");
        return errors.get(0);
    }

    @Test
    void nothingIsParsedUntilTheBodyIsRead() {
        Body<PharmacyInputs.WorkingStore> body = PharmacyBodies.of(mapper, "{ this is not json", PharmacyInputs.WorkingStore.class);
        assertThat(body).isNotNull(); // creating it never throws
        assertThatThrownBy(body::read).isInstanceOf(UnreadableBodyException.class);
    }

    @Test
    void aValidBodyIsReadAsTheClosedRecordWithPresenceKept() {
        UUID store = UUID.randomUUID();
        assertThat(PharmacyBodies.read(mapper, "{\"storeId\":\"" + store + "\"}", PharmacyInputs.WorkingStore.class).storeId())
                .isEqualTo(store);
        PharmacyInputs.UnitSettings unit = PharmacyBodies.read(mapper,
                "{\"version\":2,\"gstin\":null,\"integrationMode\":\"STANDALONE\"}", PharmacyInputs.UnitSettings.class);
        assertThat(Sent.missing(unit.gstin())).isFalse();
        assertThat(Sent.missing(unit.drugLicenceNo())).isTrue();
        assertThat(unit.integrationMode()).isEqualTo(IntegrationMode.STANDALONE);
    }

    @Test
    void aMissingBlankOrNullBodyIsMalformedRequest() {
        for (String raw : new String[] {null, "", "   ", "null"}) {
            ResponseEntity<ProblemDetail> response = problemOf(raw, PharmacyInputs.WorkingStore.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getType().toString()).isEqualTo("https://sahaj.in/problems/malformed-request");
        }
    }

    @Test
    void notJsonIsMalformedRequestAndNeverEchoesTheText() {
        ResponseEntity<ProblemDetail> response = problemOf("{ secret-text", PharmacyInputs.WorkingStore.class);
        assertThat(response.getBody().getType().toString()).isEqualTo("https://sahaj.in/problems/malformed-request");
        assertThat(response.getBody().getDetail()).doesNotContain("secret-text");
    }

    @Test
    void anUnknownFieldIsValidationAndDoesNotNameTheField() {
        ResponseEntity<ProblemDetail> response = problemOf("{\"storeId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"x\"}",
                PharmacyInputs.WorkingStore.class);
        assertThat(response.getBody().getType().toString()).isEqualTo("https://sahaj.in/problems/validation");
        assertThat(firstFieldError(response)).containsEntry("field", "body")
                .containsEntry("message", "Send only the fields listed for this request.");
        assertThat(response.getBody().getProperties().toString()).doesNotContain("tenantId");
    }

    @Test
    void aBadDateIsEnterAValidDateOnItsFieldAndAnyOtherBadValueIsChooseAValidOption() {
        ResponseEntity<ProblemDetail> date = problemOf(
                "{\"version\":0,\"integrationMode\":\"INTEGRATED\",\"drugLicenceValidTill\":\"31/03/2028\"}",
                PharmacyInputs.UnitSettings.class);
        assertThat(firstFieldError(date)).containsEntry("field", "drugLicenceValidTill")
                .containsEntry("message", "Enter a valid date");
        assertThat(date.getBody().getProperties().toString()).doesNotContain("31/03/2028");

        ResponseEntity<ProblemDetail> delegate = problemOf(
                "{\"membershipId\":\"" + UUID.randomUUID() + "\",\"storeRole\":\"DELEGATE\",\"delegateFrom\":\"tomorrow\"}",
                PharmacyInputs.MapStaff.class);
        assertThat(firstFieldError(delegate)).containsEntry("field", "delegateFrom")
                .containsEntry("message", "Enter a valid date");

        ResponseEntity<ProblemDetail> option = problemOf("{\"version\":0,\"integrationMode\":\"SOMETIMES\"}",
                PharmacyInputs.UnitSettings.class);
        assertThat(firstFieldError(option)).containsEntry("field", "integrationMode")
                .containsEntry("message", "Choose a valid option");

        ResponseEntity<ProblemDetail> version = problemOf("{\"version\":\"abc\",\"integrationMode\":\"INTEGRATED\"}",
                PharmacyInputs.UnitSettings.class);
        assertThat(firstFieldError(version)).containsEntry("field", "version")
                .containsEntry("message", "Reload the page and try again.");
    }

    @Test
    void aNestedBadDateKeepsItsPath() {
        ResponseEntity<ProblemDetail> response = problemOf(
                "{\"version\":1,\"name\":\"n\",\"code\":\"c\",\"type\":\"WARD\",\"saleAllowed\":true,"
                        + "\"consumptionAllowed\":true,\"hodMembershipId\":null,\"delegate\":{\"membershipId\":\""
                        + UUID.randomUUID() + "\",\"from\":\"2026-13-45\",\"to\":\"2026-11-30\"}}",
                PharmacyInputs.StoreUpdate.class);
        assertThat(firstFieldError(response)).containsEntry("field", "delegate.from")
                .containsEntry("message", "Enter a valid date");
    }

    @Test
    void integrationOffIs409WithTheContractsTitleAndDetail() {
        ResponseEntity<ProblemDetail> response = handler.handleIntegrationOff(new IntegrationOffException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType().toString()).isEqualTo("https://sahaj.in/problems/integration-off");
        assertThat(problem.getTitle()).isEqualTo("Not used in Standalone mode");
        assertThat(problem.getDetail())
                .isEqualTo("This works only when OPD and IPD are connected. Change Integration mode in Unit settings.");
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).isNullOrEmpty();
    }

    @Test
    void theDateOfAGoodStoreUpdateStillReads() {
        PharmacyInputs.StoreUpdate update = PharmacyBodies.read(mapper,
                "{\"version\":1,\"name\":\"n\",\"code\":\"c\",\"type\":\"WARD\",\"saleAllowed\":true,"
                        + "\"consumptionAllowed\":false,\"hodMembershipId\":null,\"delegate\":{\"membershipId\":\""
                        + UUID.randomUUID() + "\",\"from\":\"2026-11-17\",\"to\":\"2026-11-30\"}}",
                PharmacyInputs.StoreUpdate.class);
        assertThat(update.delegate().value().from()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(update.hodMembershipId().present()).isTrue();
    }
}
