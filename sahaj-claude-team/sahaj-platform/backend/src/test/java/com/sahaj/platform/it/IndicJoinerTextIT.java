package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-26 (tech lead decision) free text in IPD may hold the zero-width joiner (U+200D) and non-joiner (U+200C) that
 * Devanagari conjuncts need, while the other invisible and text-direction characters stay refused. Checked on the
 * admission reason and on the ward name, on the real {@code sahaj_test} database. Test plan: docs/test-plans/SAH-26.md.
 */
@OnTestDatabase
class IndicJoinerTextIT extends BedTransferSupport {

    private static final String ZWJ = "\u200D";
    private static final String ZWNJ = "\u200C";
    /** Ka + virama + ZWJ + Ssa (half form), and Ka + virama + ZWNJ + Ssa (explicit virama). */
    private static final String WITH_ZWJ = "\u0915\u094D" + ZWJ + "\u0937 \u0924\u093E\u092A";
    private static final String WITH_ZWNJ = "\u0915\u094D" + ZWNJ + "\u0937 \u092A\u0947\u0936\u0902\u091F";

    private JsonNode admitWithReason(Clinic clinic, String reason, int expected) throws Exception {
        Map<String, Object> m = body(newPatient(clinic), clinic.doctorMembershipId(), newBed(clinic, "gen"));
        m.put("reason", reason);
        return ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(m), expected);
    }

    private JsonNode addWard(Clinic clinic, String name, int expected) throws Exception {
        return ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":" + objectMapper.writeValueAsString(name) + "}", expected);
    }

    @Test
    void anAdmissionReasonWithDevanagariJoinersIsAcceptedAndStoredCharacterForCharacter() throws Exception {
        Clinic clinic = hospital();
        for (String reason : new String[] {WITH_ZWJ, WITH_ZWNJ, "Fever " + WITH_ZWJ + " " + WITH_ZWNJ}) {
            JsonNode created = admitWithReason(clinic, reason, 201);
            String id = created.get("id").asText();
            assertThat(created.get("reason").asText()).isEqualTo(reason);
            assertThat(detail(clinic, id).get("reason").asText()).isEqualTo(reason);
            assertThat(values(clinic, "select reason from ipd.admission where id = ?::uuid", id)).containsExactly(reason);
            assertThat(values(clinic, "select (position(chr(8205) in reason) > 0 or position(chr(8204) in reason) > 0)::text "
                    + "from ipd.admission where id = ?::uuid", id)).containsExactly("true");
        }
    }

    @Test
    void aWardNameWithDevanagariJoinersIsAcceptedAndShownBack() throws Exception {
        Clinic clinic = hospital();
        for (String name : new String[] {WITH_ZWJ, WITH_ZWNJ}) {
            JsonNode layout = addWard(clinic, name, 201);
            boolean found = false;
            for (JsonNode w : layout.get("wards")) {
                found |= name.equals(w.get("name").asText());
            }
            assertThat(found).as("ward in the layout").isTrue();
            assertThat(count(clinic, "select count(*) from ipd.ward where name = ?", name)).isOne();
        }
        // the same name twice is still a duplicate
        problem(call(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":" + objectMapper.writeValueAsString(WITH_ZWJ) + "}"),
                409);
    }

    @Test
    void zeroWidthSpaceAndByteOrderMarkAndTheOtherInvisibleAndDirectionCharactersAreStillRefused() throws Exception {
        Clinic clinic = hospital();
        String[] bad = {"\u200B", "\uFEFF", "\u200E", "\u200F", "\u2060", "\u202E", "\u2066", "\u2028", "\u0000", "\u0085"};
        Map<String, Object> base = body(newPatient(clinic), clinic.doctorMembershipId(), newBed(clinic, "gen"));
        long admissions = rows(clinic, "admission");
        long wards = rows(clinic, "ward");
        for (String ch : bad) {
            String label = "U+" + String.format("%04X", (int) ch.charAt(0));
            for (String text : new String[] {"Fever" + ch + "cough", ch + "Fever", "Fever" + ch}) {
                JsonNode r = problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(withReason(base, text))), 400);
                assertFieldError(r, "reason");
                JsonNode w = problem(call(HttpMethod.POST, WARDS, clinic.owner(),
                        "{\"name\":" + objectMapper.writeValueAsString("Ward" + ch + "A") + "}"), 400);
                assertFieldError(w, "name");
            }
            assertThat(rows(clinic, "admission")).as(label).isEqualTo(admissions);
            assertThat(rows(clinic, "ward")).as(label).isEqualTo(wards);
        }
        // a joiner next to a refused character does not make it pass
        problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(withReason(base, WITH_ZWJ + "\u200B"))), 400);
        problem(call(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":"
                + objectMapper.writeValueAsString(WITH_ZWNJ + "\uFEFF") + "}"), 400);
    }

    private static Map<String, Object> withReason(Map<String, Object> base, String reason) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        m.put("reason", reason);
        return m;
    }
}
