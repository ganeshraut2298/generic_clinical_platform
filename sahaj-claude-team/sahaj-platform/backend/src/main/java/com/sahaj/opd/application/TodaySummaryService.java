package com.sahaj.opd.application;

import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-15 Today dashboard: every OPD figure computed on the server for the Asia/Kolkata calendar day. Read only,
 * so no audit rows; names go to the response only and are never logged. "My tasks" reuses
 * {@link VisitService#today} so there is one definition of "mine"; follow-up figures come from
 * {@link FollowUpService#counts}.
 */
@Service
public class TodaySummaryService {

    private final VisitRepository visitRepository;
    private final VisitService visitService;
    private final FollowUpService followUpService;
    private final PatientLookupPort patientLookup;
    private final TeamMemberPort teamMemberPort;
    private final Clock clock;

    public TodaySummaryService(VisitRepository visitRepository, VisitService visitService,
                                FollowUpService followUpService, PatientLookupPort patientLookup,
                                TeamMemberPort teamMemberPort, Clock clock) {
        this.visitRepository = visitRepository;
        this.visitService = visitService;
        this.followUpService = followUpService;
        this.patientLookup = patientLookup;
        this.teamMemberPort = teamMemberPort;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TodaySummaryView summary(UUID tenantId, String actorRole) {
        Instant now = clock.instant();
        Instant startOfToday = LocalDate.now(clock.withZone(VisitService.INDIA))
                .atStartOfDay(VisitService.INDIA).toInstant();
        List<VisitRow> visits = new ArrayList<>(visitRepository.findSince(tenantId, startOfToday));
        visits.sort(Comparator.comparing(VisitRow::createdAt).thenComparingInt(VisitRow::tokenNo));

        int newCount = 0;
        int followUpCount = 0;
        int waiting = 0;
        int inOpd = 0;
        int longestWait = 0;
        for (VisitRow visit : visits) {
            if ("FOLLOW_UP".equals(visit.visitType())) {
                followUpCount++;
            } else {
                newCount++;
            }
            if (!VisitService.DONE.equals(visit.stage())) {
                inOpd++;
            }
            if (isWaiting(visit)) {
                waiting++;
                longestWait = Math.max(longestWait, minutesSince(visit.createdAt(), now));
            }
        }

        FollowUpView.Counts followUps = followUpService.counts(tenantId);
        return new TodaySummaryView(visits.size(), newCount, followUpCount, waiting, inOpd, longestWait,
                followUps.due(), followUpService.returnedToday(tenantId), followUps.missed(), doctors(tenantId, visits),
                myTasks(tenantId, actorRole, now));
    }

    private List<TodaySummaryView.Doctor> doctors(UUID tenantId, List<VisitRow> visits) {
        Map<UUID, PatientSummary> patients = new HashMap<>();
        List<TodaySummaryView.Doctor> result = new ArrayList<>();
        for (TeamMember doctor : teamMemberPort.findActiveDoctors(tenantId)) {
            TodaySummaryView.NowWithDoctor current = null;
            List<Integer> next = new ArrayList<>();
            for (VisitRow visit : visits) {
                if (!doctor.membershipId().equals(visit.doctorMembershipId())) {
                    continue;
                }
                if (current == null && VisitService.WITH_DOCTOR.equals(visit.stage())) {
                    PatientSummary patient = patients.computeIfAbsent(visit.patientId(),
                            id -> patientLookup.find(tenantId, id).orElse(null));
                    current = new TodaySummaryView.NowWithDoctor(visit.id(), visit.tokenNo(),
                            patient == null ? "" : patient.name(), patient == null ? 0 : patient.age(),
                            patient == null ? "O" : patient.sex(), visit.visitType());
                } else if (isWaiting(visit)) {
                    next.add(visit.tokenNo());
                }
            }
            result.add(new TodaySummaryView.Doctor(doctor.membershipId(), doctor.name(), current, next));
        }
        return result;
    }

    /** The same ownership rule as the Queue's My tasks; a finished visit needs nothing, so it is not a task. */
    private List<TodaySummaryView.Task> myTasks(UUID tenantId, String actorRole, Instant now) {
        return visitService.today(tenantId, actorRole, true).stream()
                .filter(view -> !VisitService.DONE.equals(view.stage()))
                .map(view -> new TodaySummaryView.Task(view.id(), view.tokenNo(), view.patientName(), view.stage(),
                        view.doctorName(), minutesSince(view.createdAt(), now)))
                .toList();
    }

    private static boolean isWaiting(VisitRow visit) {
        return VisitService.WAITING.equals(visit.stage()) || VisitService.VITALS.equals(visit.stage());
    }

    private static int minutesSince(Instant createdAt, Instant now) {
        return (int) Math.max(0, Duration.between(createdAt, now).toMinutes());
    }
}
