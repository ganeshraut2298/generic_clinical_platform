package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.specialty.ChemoCheckService.Prepared;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.PinInput;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Way (b) of the chemotherapy second check (SAH-66-3; ADR-023 D6, ADR-010 D9, ADR-014): the named checker types their own
 * colleague PIN at the prescriber's device. Deliberately <b>not</b> transactional, because the PIN check must run outside
 * any transaction (the platform runs its own short transaction, so a wrong try counts even when this request then
 * fails). The order is fixed: a read-only step proves that a checker is named, every check passes and the fingerprint
 * the screen holds is current; then the PIN check of {@link StaffPinPort#checkColleague} with the checker's own role; then
 * a second transaction under the order row lock re-checks the revision and the fingerprint and records the confirmation.
 * All SAH-38 rules apply unchanged (lockout, pair cap, per-caller limit, fail closed). The PIN is never stored, logged,
 * audited or echoed; the texts are the neutral checker texts of {@link StaffPinException.Subject#CHECKER}.
 */
@Service
public class ChemoSecondCheckService {

    private final ChemoCheckService checks;
    private final StaffPinPort staffPin;

    public ChemoSecondCheckService(ChemoCheckService checks, StaffPinPort staffPin) {
        this.checks = checks;
        this.staffPin = staffPin;
    }

    public ObjectNode confirmByPin(ClinicUser user, UUID recordId, PinInput input) {
        Prepared prepared = checks.preparePin(user, recordId, input);
        if (prepared.alreadyConfirmed() != null) {
            return prepared.alreadyConfirmed();
        }
        ColleaguePinCheck result = staffPin.checkColleague(user.tenantId(), user.userId(),
                prepared.checkerMembershipId(), prepared.checkerRole(), input.pin());
        if (result.outcome() == PinCheck.Outcome.NOT_ELIGIBLE) {
            throw new InvalidFieldsException(List.of(new FieldMessage("checkerMembershipId",
                    "Choose a checker from the list.")));
        }
        if (!result.isOk()) {
            throw StaffPinException.of(result.pin(), StaffPinException.Subject.CHECKER);
        }
        return checks.confirmPin(user, recordId, input, result.colleagueUserId());
    }
}
