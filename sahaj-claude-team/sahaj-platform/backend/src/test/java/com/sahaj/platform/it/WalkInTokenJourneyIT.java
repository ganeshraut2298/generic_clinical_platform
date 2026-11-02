package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Diagnostic and regression test for a human-reported live 500 on {@code POST /api/v1/opd/visits}
 * (SAH-9's "issue a walk-in token" endpoint). Static review of the whole call chain
 * (VisitController to VisitService to PatientLookupPort/TeamMemberPort to FeeSettingsService to
 * the Jdbc* repositories to AuditLog) found nothing; this test drives the real sign-up, the real
 * five-step setup wizard (SAH-4), a real "add a doctor later" through Team and access (SAH-6) and
 * a real patient registration (SAH-8) end to end against {@code sahaj_test}, then sends the exact
 * {@code VisitCreate} body the live frontend sent ({@code patientId}, {@code doctorMembershipId}
 * only, per {@code docs/api/opd.yaml}).
 *
 * <p><b>Reproduced, 100% of the time, on the very first run</b> - no special live-data shape
 * needed. Root cause: {@code JdbcVisitRepository.lastVisitAt} (SAH-9) calls
 * {@code .query(rowMapper).single()} on a {@code select max(created_at) ...} query. That query
 * always returns exactly one row, but the value is {@code null} whenever this patient has no
 * earlier visit with this doctor - which is every patient's very first token with a given doctor,
 * i.e. the common case, not an edge case. Spring's {@code JdbcClient.MappedQuerySpec.single()}
 * delegates to {@code DataAccessUtils.requiredSingleResult}, which throws
 * {@code TypeMismatchDataAccessException("Result value is null but no null value expected")} for a
 * single {@code null} result, regardless of whether {@code null} is a legitimate value for that
 * row. {@code ProblemExceptionHandler} then turns that into the generic 500 the human saw.
 *
 * <p>Fix needed (java-developer; not applied here - outside the qa-engineer's folder):
 * {@code backend/src/main/java/com/sahaj/opd/infrastructure/persistence/JdbcVisitRepository.java},
 * method {@code lastVisitAt} (around line 80-87). Stop using {@code .single()} on a value that can
 * legitimately be null; for example end the chain with {@code .list().get(0)} instead of
 * {@code .single()} (the query always returns exactly one row, so {@code list()} is safe and does
 * not apply {@code requiredSingleResult}'s null check), or rewrite the query to
 * {@code select created_at from opd.visit where ... order by created_at desc limit 1} and use
 * {@code .query(...).optional()}, which returns zero rows instead of one null row.
 */
@OnTestDatabase
class WalkInTokenJourneyIT extends AuthFlowTestSupport {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void issuingAWalkInTokenForARealPatientAndARealDoctorSucceeds() throws Exception {
        String ownerMobile = uniqueMobile();
        Session owner = signUp(ownerMobile);
        UUID tenantId = owner.tenantId();
        UUID ownerUserId = owner.userId();
        RequestPostProcessor auth = asUser(tenantId, ownerUserId, "OWNER");

        completeRealSetupWizard(auth);

        // A second doctor added later through Team and access (SAH-6) - not one of the wizard's
        // own doctors - the way a clinic that has been live for weeks grows its team.
        String doctorMobile = uniqueMobile();
        MvcResult addDoctor = mvc.perform(post("/api/v1/platform/team").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dr Asha Rao","mobile":"%s","role":"DOCTOR",
                                 "doctor":{"specialty":"General physician","feeRupees":300,"degree":"MBBS","registrationNo":"MH12345"}}
                                """.formatted(doctorMobile)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID doctorMembershipId = readId(addDoctor);

        MvcResult addPatient = mvc.perform(post("/api/v1/patient/patients").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ramesh Kulkarni","age":45,"sex":"M","mobile":"%s",
                                 "area":"Shivaji Nagar","language":"mr"}
                                """.formatted(uniqueMobile())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID patientId = readId(addPatient);

        // The exact VisitCreate body the live frontend sent: patientId and doctorMembershipId only.
        mvc.perform(post("/api/v1/opd/visits").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\""
                                + doctorMembershipId + "\"}"))
                .andExpect(status().isCreated());
    }

    /** The real SAH-4 wizard, step by step, the way the setup screen itself would submit it. */
    private void completeRealSetupWizard(RequestPostProcessor auth) throws Exception {
        step(1, auth, """
                {"name":"Sahaj test clinic","address":"12 MG Road","city":"Pune","pin":"411001",
                 "phone":"9876543210","registrationNo":"REG-001"}
                """);
        step(2, auth, "{\"code\":\"gp\"}");
        step(3, auth, """
                {"doctors":[{"name":"Dr Owner","specialty":"General physician","feeRupees":200,"mobile":""}],
                 "staff":[]}
                """);
        step(4, auth, """
                {"feeTiming":"before","queue":true,"vitals":true,"followUpDays":7,"followUpFee":"free"}
                """);
        step(5, auth, "{\"brand\":\"blue\",\"slug\":\"sahaj-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"language\":\"en\"}");
        mvc.perform(post("/api/v1/platform/setup/complete").with(auth))
                .andExpect(status().isOk());
    }

    private void step(int n, RequestPostProcessor auth, String body) throws Exception {
        mvc.perform(put("/api/v1/platform/setup/steps/" + n).with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private UUID readId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }
}
