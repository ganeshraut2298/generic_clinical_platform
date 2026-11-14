package com.sahaj.opd.application.emr.finish;

import com.sahaj.opd.application.AppointmentService;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.finish.FinishModels.FreeSlot;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-63-3, SAH-69-3, SAH-51-6 (ADR-025 D6, D7): the record doctor's next free appointment slots for the finish dialog
 * (physiotherapy "Book session n+1", the dental next sitting and the follow-up preview). From the doctor's slot settings
 * (SAH-11); leave, walk-in slots, taken slots and times already past are left out. Reads only; up to 7 days, oldest first.
 */
@Service
public class EmrFreeSlotService {

    static final int DAYS = 7;
    static final int MAX_LIMIT = 12;
    static final int DEFAULT_LIMIT = 4;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final EmrRecordService records;
    private final AppointmentService appointments;
    private final Clock clock;

    public EmrFreeSlotService(EmrRecordService records, AppointmentService appointments, Clock clock) {
        this.records = records;
        this.appointments = appointments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FreeSlot> freeSlots(ClinicUser user, UUID recordId, LocalDate from, Integer limit) {
        SignContext ctx = records.signContext(user, recordId, false);
        if (!"OPD_VISIT".equals(ctx.row().encounterType()) || ctx.encounter().visit() == null) {
            throw EmrProblemException.notApplicable();
        }
        int wanted = limit == null ? DEFAULT_LIMIT : limit;
        if (wanted < 1 || wanted > MAX_LIMIT) {
            throw new InvalidInputException("limit", "Choose 1 to 12 slots.");
        }
        LocalDate start = from == null ? LocalDate.now(clock.withZone(INDIA)).plusDays(1) : from;
        return appointments.freeSlots(user.tenantId(), ctx.encounter().visit().doctorMembershipId(), start, DAYS, wanted)
                .stream().map(s -> new FreeSlot(s.date(), s.time().toString())).toList();
    }
}
