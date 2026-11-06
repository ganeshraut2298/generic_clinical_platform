package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

/**
 * SAH-31 ADR-009 D5 and ADR-010 D3: with {@code SAHAJ_PIN_PEPPER} missing, Verify order answers 503 pin-unavailable and
 * nothing is verified; everything that needs no PIN (the queue, reject, stop, the self-verification refusal that comes
 * before the PIN) keeps working. Runs in its own Spring context with the pepper blanked. Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@TestPropertySource(properties = "sahaj.pin.pepper=")
class MedicineVerifyWithoutPepperIT extends MedicineSupport {

    private static final String DUMMY_HASH = "$2a$12$" + "a".repeat(53);

    @Test
    void verifyAnswers503PinUnavailableTheOrderStaysPendingAndNoPinTryIsCountedWhileRejectStopAndTheQueueStillWork()
            throws Exception {
        Clinic clinic = hospital();
        Staff doctor = staff(clinic, "DOCTOR");
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        asOwner(true, PIN_TABLE, c -> {
            DbAccess.execute(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", clinic.tenantId().toString(),
                    pharmacist.membershipId().toString(), DUMMY_HASH, pharmacist.userId().toString());
            return null;
        });
        Adm adm = admitWithAllergy(clinic, "None known");
        String id = orderIdOf(place(doctor.auth(), adm.id(), "pcm"));
        String other = orderIdOf(place(doctor.auth(), adm.id(), "panto"));
        String third = orderIdOf(place(doctor.auth(), adm.id(), "ondan"));
        String token = tokenOfOrder(pharmacist.auth(), id);
        MedSnap before = medSnap(clinic, adm);

        for (String pin : new String[] {"4827", "0000"}) {
            JsonNode p = problem(verifyCall(pharmacist, id, pin, token), 503);
            assertType(p, "pin-unavailable");
            assertThat(p.get("detail").asText()).isEqualTo(PIN_UNAVAILABLE);
        }
        assertThat(medSnap(clinic, adm)).as("nothing verified, no try counted").isEqualTo(before);
        assertThat(state(clinic, id).status()).isEqualTo("PENDING");
        assertThat(pinStatus(pharmacist).get("available").asBoolean()).isFalse();

        // the checks before the PIN still answer first
        assertType(problem(verifyCall(pharmacist, id, "12a4", token), 400), "validation");
        assertType(problem(verifyCall(newPn(clinic, "NURSE"), id, "4827", token), 403), "forbidden");

        // what needs no PIN keeps working
        assertThat(queue(pharmacist.auth()).get("waiting").asInt()).isEqualTo(3);
        rejectOk(pharmacist.auth(), other, "WRONG_DOSE");
        stopOk(doctor.auth(), third);
        assertThat(state(clinic, other).status()).isEqualTo("REJECTED");
        assertThat(state(clinic, third).status()).isEqualTo("STOPPED");

        // the owner's own order: self-verification is refused before the PIN is looked at
        String own = orderIdOf(place(ownerStaff(clinic).auth(), adm.id(), "met"));
        Pn owner = ownerPn(clinic);
        JsonNode self = problem(verifyCall(owner, own, "4827", "AAAAAAAAAAAAAAAAAAAAAA"), 403);
        assertType(self, "self-verification");
        assertThat(ok(HttpMethod.GET, ME_PIN, pharmacist.auth(), null, 200).get("state").asText()).isEqualTo("SET");
    }
}
