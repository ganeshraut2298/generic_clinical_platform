package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MedicineAdministration.Prepared;
import com.sahaj.ipd.application.MedicineAdministration.Recorded;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.StaffPinPort;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * SAH-32 giving a dose or recording that it was not given: the endpoint's entry point (ADR-011 D6).
 *
 * <p>This class is deliberately <b>not</b> transactional, for the reason {@link PharmacistCheckService#verify} is not:
 * the second nurse's PIN must be checked outside any transaction, in its own platform transaction that commits first, so
 * a wrong try is counted although the request then fails and no platform row lock is held together with the admission
 * lock. The three steps are the first IPD transaction ({@link MedicineAdministration#prepare}, no lock), the PIN check,
 * and the locked transaction ({@link MedicineAdministration#recordLocked}). Both transactional methods are on another
 * bean, so each call goes through the transaction proxy.
 *
 * <p>A wristband mismatch is answered 409, but its clinical event and audit row must stay: the transaction that wrote
 * them returns a result and commits, and only this class throws afterwards.
 */
@Service
public class DoseService {

    static final String MISMATCH_TITLE = "Wristband does not match";

    private final MedicineAdministration administration;
    private final StaffPinPort staffPin;

    DoseService(MedicineAdministration administration, StaffPinPort staffPin) {
        this.administration = administration;
        this.staffPin = staffPin;
    }

    /** The answer and whether it is a new record (201) or a replay (200). */
    public record DoseResult(DoseRecorded answer, boolean created) {

        @Override
        public String toString() {
            return "DoseResult[created=" + created + "]";
        }
    }

    public DoseResult record(UUID tenantId, UUID actor, String role, UUID orderId, DoseInput input) {
        Prepared prepared = administration.prepare(tenantId, actor, role, orderId, input);
        switch (prepared.kind()) {
            case REPLAY:
                return new DoseResult(prepared.replay(), false);
            case MISMATCH:
                throw mismatch();
            default:
                break;
        }
        UUID colleagueUserId = null;
        if (prepared.pinRequired()) {
            colleagueUserId = staffPin.checkColleague(tenantId, actor, prepared.secondNurseId(),
                    MedicineAdministration.NURSE_ROLE, prepared.pin()).requireOk();
        }
        Recorded recorded = administration.recordLocked(tenantId, actor, role, orderId, input.withoutPin(),
                colleagueUserId);
        switch (recorded.kind()) {
            case MISMATCH:
                throw mismatch();
            case REPLAYED:
                return new DoseResult(recorded.answer(), false);
            default:
                return new DoseResult(recorded.answer(), true);
        }
    }

    /** 409 wristband-mismatch: no name in a problem detail (the client builds the toast from the MAR it holds). */
    private static ConflictException mismatch() {
        return new ConflictException("wristband-mismatch", MISMATCH_TITLE, DoseRules.MISMATCH);
    }
}
