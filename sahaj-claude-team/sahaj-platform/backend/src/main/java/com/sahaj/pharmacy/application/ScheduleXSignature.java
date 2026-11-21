package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.TeamRoles;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The Schedule X second signature (rule 19, BR-32, ADR-029 P7). A Schedule X handling needs a pharmacist and a second person:
 * another ACTIVE pharmacist or owner of the clinic, never the caller.
 *
 * <p>{@link #verify} calls {@code StaffPinPort.checkColleague} and so must run <b>outside any transaction</b>, before the
 * posting transaction: first with the role PHARMACIST and, when that answers NOT_ELIGIBLE (nothing compared or counted), once
 * more with OWNER. The outcomes are the platform's: {@code wrong-pin} 403, {@code pin-locked} 403, {@code pin-not-set} 409,
 * {@code rate-limit} 429, {@code colleague-pin-limit} 429, {@code pin-unavailable} 503 and {@code pin-check-failed} 503; not
 * eligible at all is 403 {@code second-signer-invalid}. The posting transaction then calls {@link #recheck}, which reads the
 * signer again through the team port (still ACTIVE, still PHARMACIST or OWNER, not the caller). The PIN is never stored, logged,
 * audited or echoed.
 */
@Component
public class ScheduleXSignature {

    static final String INVALID = "The second signer must be another active pharmacist or owner.";
    static final String NEEDED = "A second signature is needed for Schedule X items.";
    static final String ONLY_PHARMACIST = "Only a pharmacist can handle Schedule X items, with a second signature.";

    /** L17: refused before any PIN check, so a malformed try is never counted against the colleague. */
    static final String PIN_FORMAT_MESSAGE = "Enter a 4-digit PIN.";
    private static final Pattern PIN_FORMAT = Pattern.compile("^[0-9]{4}$");

    private final StaffPinPort pins;
    private final TeamMemberPort team;

    public ScheduleXSignature(StaffPinPort pins, TeamMemberPort team) {
        this.pins = pins;
        this.team = team;
    }

    /** Checks the colleague's PIN. Returns the signer's membership id for a right PIN; otherwise throws the platform's problem. */
    public UUID verify(UUID tenantId, UUID callerUserId, UUID signerMembershipId, String pin) {
        if (signerMembershipId == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", NEEDED)));
        }
        if (pin == null || !PIN_FORMAT.matcher(pin).matches()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner.pin", PIN_FORMAT_MESSAGE)));
        }
        ColleaguePinCheck check = pins.checkColleague(tenantId, callerUserId, signerMembershipId, "PHARMACIST", pin);
        if (check.outcome() == PinCheck.Outcome.NOT_ELIGIBLE) {
            check = pins.checkColleague(tenantId, callerUserId, signerMembershipId, "OWNER", pin);
        }
        if (check.outcome() == PinCheck.Outcome.NOT_ELIGIBLE) {
            throw invalid();
        }
        check.requireOk();
        return signerMembershipId;
    }

    /** Inside the posting transaction: the signer is still ACTIVE, a pharmacist or an owner, and not the caller. */
    public void recheck(UUID tenantId, UUID callerMembershipId, UUID signerMembershipId) {
        TeamMember signer = team.find(tenantId, signerMembershipId).orElse(null);
        boolean eligible = signer != null && signer.active() && !signerMembershipId.equals(callerMembershipId)
                && (TeamRoles.PHARMACIST.equals(signer.role()) || TeamRoles.OWNER.equals(signer.role()));
        if (!eligible) {
            throw invalid();
        }
    }

    static ForbiddenException invalid() {
        return new ForbiddenException("second-signer-invalid", "Second signer not valid", INVALID);
    }

    static ForbiddenException onlyPharmacist() {
        return new ForbiddenException("schedule-x-pharmacist", "Pharmacist needed", ONLY_PHARMACIST);
    }
}
