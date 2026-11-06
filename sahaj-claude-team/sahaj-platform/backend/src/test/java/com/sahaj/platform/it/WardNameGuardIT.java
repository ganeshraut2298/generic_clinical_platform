package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-26 security review L1 (ward look-alikes and the zero-width joiner rule), on the real {@code sahaj_test}
 * database: ward names that differ only by U+200C / U+200D are duplicates in one clinic (index and API, 409 with a
 * plain message) and independent in two; a joiner is accepted only directly after a virama and before a letter
 * (IDNA CONTEXTJ), for ward names and for the reason for admission; text is stored in NFC. Test plan:
 * docs/test-plans/SAH-27.md (SAH-26 guard tests).
 */
@OnTestDatabase
class WardNameGuardIT extends BedTransferSupport {

    private static final String ZWJ = "‍";
    private static final String ZWNJ = "‌";
    private static final String VIRAMA = "्";
    /** Ja + virama + Nya + aa + Na ("gyaan"), written plain and with a joiner after the virama. */
    private static final String PLAIN = "ज" + VIRAMA + "ञान";
    private static final String WITH_ZWJ = "ज" + VIRAMA + ZWJ + "ञान";
    private static final String WITH_ZWNJ = "ज" + VIRAMA + ZWNJ + "ञान";

    private static final String INSERT_WARD = "insert into ipd.ward (id, name, created_at, created_by) values (?, ?, now(), ?)";

    private org.springframework.test.web.servlet.ResultActions addWardCall(Clinic clinic, String name) throws Exception {
        return call(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":" + objectMapper.writeValueAsString(name) + "}");
    }

    private JsonNode addWard(Clinic clinic, String name) throws Exception {
        return ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":" + objectMapper.writeValueAsString(name) + "}", 201);
    }

    private SQLException insertWard(Clinic clinic, String name) {
        try {
            db().inTenant(clinic.tenantId(), c -> {
                DbAccess.execute(c, INSERT_WARD, UUID.randomUUID(), name, clinic.ownerUserId());
                return null;
            });
        } catch (SQLException e) {
            return e;
        }
        return null;
    }

    // ---------------- duplicates ----------------

    @Test
    void theIndexRefusesWardNamesThatDifferOnlyByJoinersOrCaseInOneClinicAndAllowsThemInTwo() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        commitAsTenant(a.tenantId(), INSERT_WARD, UUID.randomUUID(), "ICU", a.ownerUserId());       // committed
        for (String twin : new String[] {"IC" + ZWJ + "U", "IC" + ZWNJ + "U", "I" + ZWJ + "CU", "ICU" + ZWJ, "icu", "ICU",
                "Ic" + ZWJ + ZWNJ + "u"}) {
            SQLException error = insertWard(a, twin);
            assertError(error).as("twin of ICU: " + twin.replace(ZWJ, "<ZWJ>").replace(ZWNJ, "<ZWNJ>")).isNotNull();
            assertThat(error.getSQLState()).isEqualTo("23505");
            if (twin.contains(ZWJ) || twin.contains(ZWNJ)) {
                assertThat(error.getMessage()).contains("ux_ward_tenant_name_nojoiners");
            }
        }
        assertError(insertWard(a, "ICU ward")).as("a different name is fine").isNull();
        // another clinic is independent: ICU and the look-alike are both accepted there
        assertError(insertWard(b, "ICU")).isNull();
        assertError(insertWard(b, "IC" + ZWJ + "U")).isNull();
        assertThat(count(a, "select count(*) from ipd.ward where lower(name) like 'ic%'")).isOne();
    }

    @Test
    void theApiAnswers409WardExistsWhenOnlyJoinersOrCaseDifferAndAcceptsTheSameNameInAnotherClinic() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        long wards = rows(a, "ward");
        addWard(a, PLAIN);
        for (String twin : new String[] {WITH_ZWJ, WITH_ZWNJ}) {
            JsonNode p = problem(addWardCall(a, twin), 409);
            assertType(p, "ward-exists");
            assertThat(p.get("detail").asText()).isEqualTo("A ward with this name already exists.");
        }
        // the other way round: the joined spelling first
        addWard(a, "Ward " + WITH_ZWJ);
        assertType(problem(addWardCall(a, "Ward " + PLAIN), 409), "ward-exists");
        assertType(problem(addWardCall(a, "WARD " + WITH_ZWNJ), 409), "ward-exists");
        assertThat(rows(a, "ward")).isEqualTo(wards + 2);
        // another clinic takes both spellings
        addWard(b, PLAIN);
        assertThat(count(b, "select count(*) from ipd.ward where name = ?", PLAIN)).isOne();
    }

    @Test
    void theLookAlikeIcuWithAJoinerInTheMiddleIsRefusedPlainlyAndNeverAPlainServerError() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        addWard(a, "ICU");
        long wards = rows(a, "ward");
        JsonNode p = problem(addWardCall(a, "IC" + ZWJ + "U"), 400);       // a joiner after a Latin letter is not allowed at all
        assertFieldError(p, "name");
        assertThat(rows(a, "ward")).isEqualTo(wards);
        assertType(problem(addWardCall(a, "ICU"), 409), "ward-exists");
        assertType(problem(addWardCall(a, "icu"), 409), "ward-exists");
        addWard(b, "ICU");                                                  // allowed in another clinic
    }

    // ---------------- where a joiner may stand ----------------

    @Test
    void aJoinerIsAcceptedOnlyDirectlyAfterAViramaAndBeforeALetterInWardNamesAndAdmissionReasons() throws Exception {
        Clinic clinic = hospital();
        String bengali = "ক্" + ZWJ + "ষ";
        String tamil = "க்" + ZWNJ + "ஷ";
        String malayalam = "ക്" + ZWJ + "ഷ";
        String sinhala = "ක්" + ZWJ + "ෂ";
        String[] good = {WITH_ZWJ, WITH_ZWNJ, bengali, tamil, malayalam, sinhala, "Ward " + WITH_ZWJ + " 2"};
        int n = 0;
        for (String name : good) {
            addWard(clinic, "W" + (n++) + " " + name);
            JsonNode created = ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(withReason(clinic, name)), 201);
            assertThat(created.get("reason").asText()).isEqualTo(name);
        }
        String[] bad = {ZWJ + "ICU", "ICU" + ZWJ, ZWNJ + "ICU", "ICU" + ZWNJ,                      // first and last
                "IC" + ZWJ + "U", "IC" + ZWNJ + "U",                                              // after a Latin letter
                "ज" + VIRAMA + ZWJ + ZWJ + "ञ", "ज" + VIRAMA + ZWJ + ZWNJ + "ञ", // doubled
                "ज" + VIRAMA + ZWJ, "ज" + VIRAMA + ZWJ + " ward",                         // last, or before a space
                "ज" + ZWJ + "ञ",                                                        // after a letter that is no virama
                "ज " + ZWJ + "ञ",                                                  // after a space
                "ज" + VIRAMA + ZWJ + "1"};                                                   // before a digit
        long wards = rows(clinic, "ward");
        long admissions = rows(clinic, "admission");
        for (String text : bad) {
            String label = text.replace(ZWJ, "<ZWJ>").replace(ZWNJ, "<ZWNJ>");
            assertFieldError(problem(addWardCall(clinic, text), 400), "name");
            assertFieldError(problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(withReason(clinic, text))), 400), "reason");
            assertThat(rows(clinic, "ward")).as(label).isEqualTo(wards);
            assertThat(rows(clinic, "admission")).as(label).isEqualTo(admissions);
        }
    }

    // ---------------- NFC ----------------

    @Test
    void wardNamesAreStoredInNfcSoComposedAndDecomposedSpellingsAreOneName() throws Exception {
        // SAH-26 review L1 fix text: "NFC-normalise before check and store"
        Clinic clinic = hospital();
        String composed = "Café ward";
        String decomposed = "Café ward";
        assertThat(Normalizer.normalize(decomposed, Normalizer.Form.NFC)).isEqualTo(composed);
        addWard(clinic, decomposed);
        assertThat(values(clinic, "select name from ipd.ward where name like 'Caf%'")).containsExactly(composed);
        assertType(problem(addWardCall(clinic, composed), 409), "ward-exists");
        assertType(problem(addWardCall(clinic, decomposed), 409), "ward-exists");
    }

    private Map<String, Object> withReason(Clinic clinic, String reason) throws Exception {
        Map<String, Object> m = body(newPatient(clinic), clinic.doctorMembershipId(), newBed(clinic, "gen"));
        m.put("reason", reason);
        return m;
    }
}
