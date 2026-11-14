package com.sahaj.opd.application.emr.finish;

import com.sahaj.opd.api.EmrEmergencyPort;
import com.sahaj.opd.api.EmrIpdOrderPort;
import com.sahaj.opd.api.EmrVitalsSource;
import com.sahaj.opd.api.EmrVitalsSource.VitalsReading;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-68-1 and SAH-67-1 (ADR-025 D8, D12): what the doctor's EMR reads from other places for the encounter. For an IPD round
 * the nursing-chart vitals of the admission on the round date (read through ipd, with the scope already proven) and the last
 * entry before that day; for an OPD visit and an Emergency assessment no nurse vitals are captured today (answered
 * NOT_CAPTURED, ADR-025 H10). Also the IPD medicine options of the round's Prescription. Screen only: nothing is logged.
 */
@Service
public class EmrEncounterVitalsService {

    public record Temperature(BigDecimal value, String unit) {
    }

    public record Reading(UUID sourceEntryId, String source, String recordedByName, Instant recordedAt,
                          Integer bpSystolic, Integer bpDiastolic, Integer pulse, Integer spo2, Temperature temperature,
                          Integer respiratoryRate) {

        @Override
        public String toString() {
            return "Reading[sourceEntryId=" + sourceEntryId + "]";
        }
    }

    public record Earlier(String recordedByName, Instant recordedAt) {
    }

    public record Sources(String opd, String ipd, String er) {
    }

    public record EncounterVitals(Reading current, Earlier earlier, Sources sources) {
    }

    private final EmrRecordService records;
    private final ObjectProvider<EmrVitalsSource> vitals;
    private final ObjectProvider<EmrIpdOrderPort> ipdOrders;
    private final ObjectProvider<EmrEmergencyPort> emergency;
    private final TeamMemberPort teamMembers;
    private final EmrFinishRepository finish;

    public EmrEncounterVitalsService(EmrRecordService records, ObjectProvider<EmrVitalsSource> vitals,
                                     ObjectProvider<EmrIpdOrderPort> ipdOrders, ObjectProvider<EmrEmergencyPort> emergency,
                                     TeamMemberPort teamMembers, EmrFinishRepository finish) {
        this.records = records;
        this.vitals = vitals;
        this.ipdOrders = ipdOrders;
        this.emergency = emergency;
        this.teamMembers = teamMembers;
        this.finish = finish;
    }

    @Transactional(readOnly = true)
    public EncounterVitals vitals(ClinicUser user, UUID recordId) {
        SignContext ctx = records.signContext(user, recordId, false);
        String type = ctx.row().encounterType();
        if (!"IPD_ROUND".equals(type)) {
            return new EncounterVitals(null, null, new Sources("NOT_CAPTURED", "NOT_APPLICABLE", "NOT_CAPTURED"));
        }
        EmrVitalsSource source = vitals.getIfAvailable();
        UUID tenantId = user.tenantId();
        Reading current = null;
        Earlier earlier = null;
        if (source != null && ctx.row().roundDate() != null) {
            current = source.latestNursing(tenantId, ctx.row().ipdAdmissionId(), ctx.row().roundDate())
                    .map(r -> reading(tenantId, r)).orElse(null);
            earlier = source.lastNursingBefore(tenantId, ctx.row().ipdAdmissionId(), ctx.row().roundDate())
                    .map(r -> new Earlier(name(tenantId, r.recordedByUserId()), r.recordedAt())).orElse(null);
        }
        return new EncounterVitals(current, earlier, new Sources("NOT_CAPTURED", "AVAILABLE", "NOT_CAPTURED"));
    }

    /** The IPD medicine catalogue, frequencies and override reasons for the round's Prescription (400 not-applicable otherwise). */
    @Transactional(readOnly = true)
    public EmrIpdOrderPort.OrderOptions ipdOrderOptions(ClinicUser user, UUID recordId) {
        SignContext ctx = records.signContext(user, recordId, false);
        EmrIpdOrderPort port = ipdOrders.getIfAvailable();
        if (!"IPD_ROUND".equals(ctx.row().encounterType()) || port == null) {
            throw EmrProblemException.notApplicable();
        }
        return port.options(user.tenantId());
    }

    private Reading reading(UUID tenantId, VitalsReading r) {
        return new Reading(r.entryId(), "IPD_NURSING_CHART", name(tenantId, r.recordedByUserId()), r.recordedAt(),
                r.bpSystolic(), r.bpDiastolic(), r.pulse(), r.spo2(), new Temperature(r.temperatureC(), "°C"),
                r.respiratoryRate());
    }

    private String name(UUID tenantId, UUID userId) {
        if (userId == null) {
            return "Nurse";
        }
        return teamMembers.findActiveByUserId(tenantId, userId).map(TeamMember::name).orElse("Nurse");
    }
}
