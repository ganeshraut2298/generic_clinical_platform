package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinPort;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

/**
 * SAH-31 ADR-010 D3: the application runs with {@code SAHAJ_PIN_PEPPER} missing (an OPD-only clinic needs no PIN), and
 * every PIN set and check fails closed. Runs in its own Spring context with the pepper blanked. Owner reset stays allowed
 * (clearing a PIN is the safe direction). The invalid pepper (the application refuses to start) is
 * {@link StaffPinStartupIT}. The verify answering 503 is in {@link MedicineVerifyWithoutPepperIT}. Test plan:
 * docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@TestPropertySource(properties = "sahaj.pin.pepper=")
class StaffPinPepperIT extends PinTestSupport {

    @Autowired
    private StaffPinPort port;

    private static final String DUMMY_HASH = "$2a$12$" + "a".repeat(53);

    /** A row for a member written by the owner (the application cannot hash without a pepper). */
    private void insertRow(Pn p) throws SQLException {
        asOwner(true, PIN_TABLE, c -> {
            DbAccess.execute(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, "
                    + "set_at, created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", p.clinic().tenantId().toString(),
                    p.membershipId().toString(), DUMMY_HASH, p.userId().toString());
            return null;
        });
    }

    @Test
    void theStatusSaysNotAvailableAndEverySetAndCodeRequestAnswers503PinUnavailableWritingNothing() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        JsonNode status = pinStatus(p);
        assertThat(status.get("available").asBoolean()).isFalse();
        assertThat(status.get("state").asText()).isEqualTo("NOT_SET");
        long challenges = count(clinic, "select count(*) from platform.otp_challenge where mobile = ?", p.mobile());

        allowResend(p.mobile());
        JsonNode code = problem(call(HttpMethod.POST, ME_PIN_CODE, p.auth(), null), 503);
        assertType(code, "pin-unavailable");
        assertThat(code.get("detail").asText()).isEqualTo(PIN_UNAVAILABLE);
        assertThat(code.get("title").asText()).isEqualTo("PIN checks are off");
        assertThat(count(clinic, "select count(*) from platform.otp_challenge where mobile = ?", p.mobile()))
                .as("no code is sent when the PIN cannot be saved").isEqualTo(challenges);

        // before the body is looked at, whatever it holds
        for (String body : new String[] {pinBody("123456", "4827", "4827"), pinBody(null, null, null), "{}"}) {
            JsonNode put = problem(call(HttpMethod.PUT, ME_PIN, p.auth(), body), 503);
            assertType(put, "pin-unavailable");
            assertThat(put.get("detail").asText()).isEqualTo(PIN_UNAVAILABLE);
        }
        assertThat(pinRow(clinic, p.membershipId()).exists()).isFalse();
        assertThat(audit(clinic, "PLATFORM_PIN_SET")).isEmpty();
    }

    @Test
    void theCheckAnswersUnavailableAndCountsNothingEvenForARowThatExists() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        insertRow(p);
        PinRow before = pinRow(clinic, p.membershipId());
        for (String pin : new String[] {"4827", "0000", "abcd"}) {
            freshPinAllowance(p);
            PinCheck r = port.check(clinic.tenantId(), p.userId(), pin);
            assertThat(r.outcome()).isEqualTo(PinCheck.Outcome.UNAVAILABLE);
        }
        assertThat(pinRow(clinic, p.membershipId())).isEqualTo(before);
        assertThat(pinStatus(p).get("state").asText()).isEqualTo("SET");
    }

    @Test
    void theOwnerResetStillWorksWithoutAPepper() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        insertRow(p);
        JsonNode member = resetPin(clinic, p.membershipId());
        assertThat(member.get("pin").get("state").asText()).isEqualTo("NOT_SET");
        PinRow row = pinRow(clinic, p.membershipId());
        assertThat(row.hasPin()).isFalse();
        assertThat(row.reason()).isEqualTo("RESET_BY_OWNER");
        assertThat(audit(clinic, "PLATFORM_PIN_RESET")).hasSize(1);
    }
}
