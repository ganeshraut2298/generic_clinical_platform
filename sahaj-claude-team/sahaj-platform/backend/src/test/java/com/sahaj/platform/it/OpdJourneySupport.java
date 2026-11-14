package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up for the SAH-11 / SAH-12 database-backed journeys: a real sign-up, the real five-step
 * setup wizard, a doctor and patients added through the real Team and Patient endpoints. Everything
 * runs through MockMvc against {@code sahaj_test}; nothing is mocked.
 */
abstract class OpdJourneySupport extends AuthFlowTestSupport {

    @Autowired
    protected ObjectMapper objectMapper;

    /** A fully set-up clinic with one added doctor. */
    protected record Clinic(UUID tenantId, UUID ownerUserId, UUID doctorMembershipId) {

        RequestPostProcessor owner() {
            return asUser(tenantId, ownerUserId, "OWNER");
        }

        /**
         * The role comes from the token authorities; the user id stays the owner's real user (a real
         * membership row), which is enough for FK-bearing audit columns. OwnerGuard still re-reads the
         * DB, so a non-owner role can never reach an owner-only write through it. A flag that the service
         * reads from the membership (such as {@code canEdit}) still sees the owner: tests of those use a
         * real member's token (see {@code EmrSupport.addStaff}).
         */
        RequestPostProcessor as(String role) {
            return asUser(tenantId, ownerUserId, role);
        }
    }

    protected Clinic newClinic() throws Exception {
        return newClinic(true);
    }

    protected Clinic newClinic(boolean vitals) throws Exception {
        return newClinic(vitals, true);
    }

    /**
     * {@code ownerIsDoctor} false: the wizard's doctor list is empty, so the owner gets no doctor profile (the wizard gives
     * the first doctor of step 3 to the owner). Only for tests of "an owner without doctor details".
     */
    protected Clinic newClinic(boolean vitals, boolean ownerIsDoctor) throws Exception {
        Session session = signUp(uniqueMobile());
        RequestPostProcessor auth = asUser(session.tenantId(), session.userId(), "OWNER");
        completeWizard(auth, vitals, ownerIsDoctor);
        MvcResult addDoctor = mvc.perform(post("/api/v1/platform/team").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dr Asha Rao","mobile":"%s","role":"DOCTOR",
                                 "doctor":{"specialty":"General physician","feeRupees":300,"degree":"MBBS","registrationNo":"MH12345"}}
                                """.formatted(uniqueMobile())))
                .andExpect(status().isCreated())
                .andReturn();
        return new Clinic(session.tenantId(), session.userId(), readId(addDoctor));
    }

    protected UUID newPatient(Clinic clinic) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/patient/patients").with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ramesh Kulkarni","age":45,"sex":"M","mobile":"%s",
                                 "area":"Shivaji Nagar","language":"mr"}
                                """.formatted(uniqueMobile())))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode getJson(String path, RequestPostProcessor auth) throws Exception {
        return json(mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andReturn());
    }

    protected UUID readId(MvcResult result) throws Exception {
        return UUID.fromString(json(result).get("id").asText());
    }

    private void completeWizard(RequestPostProcessor auth, boolean vitals, boolean ownerIsDoctor) throws Exception {
        step(1, auth, """
                {"name":"Sahaj test clinic","address":"12 MG Road","city":"Pune","pin":"411001",
                 "phone":"9876543210","registrationNo":"REG-001"}
                """);
        step(2, auth, "{\"code\":\"gp\"}");
        step(3, auth, ownerIsDoctor ? """
                {"doctors":[{"name":"Dr Owner","specialty":"General physician","feeRupees":200,"mobile":""}],
                 "staff":[]}
                """ : """
                {"doctors":[],"staff":[]}
                """);
        step(4, auth, "{\"feeTiming\":\"before\",\"queue\":true,\"vitals\":" + vitals
                + ",\"followUpDays\":7,\"followUpFee\":\"free\"}");
        step(5, auth, "{\"brand\":\"blue\",\"slug\":\"" + UniqueSlugs.next() + "\",\"language\":\"en\"}");
        mvc.perform(post("/api/v1/platform/setup/complete").with(auth)).andExpect(status().isOk());
    }

    private void step(int n, RequestPostProcessor auth, String body) throws Exception {
        mvc.perform(put("/api/v1/platform/setup/steps/" + n).with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
