package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * SAH-32 ADR-011 D7 and ADR-010 D3: with {@code SAHAJ_PIN_PEPPER} missing, the dose of a high-alert medicine answers 503
 * pin-unavailable and nothing is recorded or counted; everything that needs no second nurse's PIN (the checks before it, a dose
 * that is not high-alert, a high-alert dose recorded as not given) keeps working. Runs in its own Spring context with the pepper
 * blanked, so the PIN of the second nurse is a stored row made by the table owner (a PIN cannot be set without the pepper) and
 * the order is made ACTIVE by the update the verify would make. Test plan: docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@TestPropertySource(properties = "sahaj.pin.pepper=")
class DoseWithoutPepperIT extends MarSupport {

    private static final String DUMMY_HASH = "$2a$12$" + "a".repeat(53);

    @Autowired
    private StaffPinPort port;

    private String activate(Clinic clinic, Adm adm, Staff doctor, String drug) throws Exception {
        String id = orderIdOf(place(doctor.auth(), adm.id(), drug));
        commitAsTenant(clinic.tenantId(), "update ipd.medicine_order set status = 'ACTIVE', verified_by = ?::uuid, version = version + 1 "
                + "where id = ?::uuid and status = 'PENDING'", UUID.randomUUID().toString(), id);
        java.time.LocalDate before = day.minusDays(1);
        verifiedAt(clinic, id, istOn(before, 6, 0), istOn(before, 7, 0));
        return id;
    }

    @Test
    void aHighAlertGiveAnswers503PinUnavailableNothingIsRecordedOrCountedAndWhatNeedsNoPinKeepsWorking() throws Exception {
        Clinic clinic = hospital();
        Staff doctor = staff(clinic, "DOCTOR");
        Pn nurse = newPn(clinic, "NURSE");
        Pn nurse2 = newPn(clinic, "NURSE");
        asOwner(true, PIN_TABLE, c -> {
            DbAccess.execute(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", clinic.tenantId().toString(),
                    nurse2.membershipId().toString(), DUMMY_HASH, nurse2.userId().toString());
            return null;
        });
        Adm adm = admitWithAllergy(clinic, "None known");
        String uhid = values(clinic, "select uhid from patient.patient where id = ?::uuid", adm.patientId()).get(0);
        Ward w = new Ward(clinic, doctor, nurse, nurse2, "4827", null, null, adm, uhid);
        String hep = activate(clinic, adm, doctor, "hep");
        String ins = activate(clinic, adm, doctor, "ins");
        String pcm = activate(clinic, adm, doctor, "pcm");
        clockAt(8, 5);
        DSnap before = dsnap(w);

        for (String pin : new String[] {"4827", "0000"}) {
            JsonNode p = refused(doseCall(nurse.auth(), hep, givenHighAlert(w, ist(8, 0), nurse2, pin)), 503, "pin-unavailable",
                    PIN_UNAVAILABLE);
            assertThat(p.toString()).doesNotContain(pin.equals("4827") ? "\"4827\"" : "\"0000\"");
        }
        DSnap now = dsnap(w);
        assertThat(now.doses()).isEqualTo(before.doses());
        assertThat(now.events()).isEqualTo(before.events());
        assertThat(now.audits()).isEqualTo(before.audits());
        assertThat(now.pins()).as("no try counted").isEqualTo(before.pins());
        assertThat(slot(mar(nurse.auth(), adm.id()), hep, 0).get("state").asText()).isEqualTo("DUE");

        // the checks that come before the PIN still answer first
        refused(doseCall(nurse.auth(), hep, set(givenHighAlert(w, ist(8, 0), nurse2, "4827"), "wristband", "WRONG-00000")), 409,
                "wristband-mismatch", MISMATCH_TEXT);
        invalid(doseCall(nurse.auth(), hep, set(givenHighAlert(w, ist(8, 0), nurse2, "4827"), "identityConfirmed", false)),
                "identityConfirmed", CONFIRM_TEXT);
        invalid(doseCall(nurse.auth(), hep, givenHighAlert(w, ist(8, 0), nurse, "4827")), "secondNurseId", DIFFERENT_NURSE);
        refused(doseCall(nurse.auth(), hep, givenHighAlert(w, ist(8, 0), nurse2, "12a4")), 400, "validation", HIGH_ALERT_TEXT);

        // what needs no second nurse's PIN keeps working
        doseOk(nurse.auth(), pcm, given(w, ist(8, 0)));
        doseOk(nurse.auth(), ins, notGiven(ist(8, 0), "NIL_BY_MOUTH"));
        assertThat(doseDb(clinic, ins, ist(8, 0)).highAlert()).isTrue();
        assertThat(dsnap(w).pins()).isEqualTo(before.pins());
        assertThat(pinStatus(nurse2).get("available").asBoolean()).isFalse();
    }

    @Test
    void thePortAnswersUnavailableForAColleagueCheckAndWritesNothing() throws Exception {
        Clinic clinic = hospital();
        Pn caller = newPn(clinic, "NURSE");
        Pn colleague = newPn(clinic, "NURSE");
        ColleaguePinCheck r = port.checkColleague(clinic.tenantId(), caller.userId(), colleague.membershipId(), "NURSE", "4827");
        assertThat(r.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThatThrownBy(r::requireOk).isInstanceOfSatisfying(StaffPinException.class, e -> {
            assertThat(e.slug()).isEqualTo("pin-unavailable");
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.getMessage()).isEqualTo(PIN_UNAVAILABLE);
        });
        assertThat(pinRow(clinic, colleague.membershipId()).exists()).isFalse();
        assertThat(audit(clinic, "PLATFORM_PIN_COLLEAGUE_CHECK_FAILED")).isEmpty();
    }
}
