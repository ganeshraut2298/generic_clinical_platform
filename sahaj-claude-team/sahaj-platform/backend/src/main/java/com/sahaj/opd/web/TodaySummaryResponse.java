package com.sahaj.opd.web;

import com.sahaj.opd.application.TodaySummaryView;
import java.util.List;
import java.util.UUID;

/** {@code TodaySummary} in {@code docs/api/opd.yaml} (SAH-15). */
public record TodaySummaryResponse(int patientsTotal, int newCount, int followUpCount, int waitingCount,
                                    int inOpdCount, int longestWaitMinutes, int followUpsDue, int followUpsReturned,
                                    int followUpsMissed, List<Doctor> doctors, List<Task> myTasks) {

    public record Doctor(UUID doctorMembershipId, String doctorName, NowWithDoctor nowWithDoctor,
                          List<Integer> nextTokens) {
    }

    public record NowWithDoctor(UUID visitId, int tokenNo, String patientName, int age, String sex,
                                 String visitType) {
    }

    public record Task(UUID visitId, int tokenNo, String patientName, String stage, String doctorName,
                        int waitMinutes) {
    }

    public static TodaySummaryResponse from(TodaySummaryView v) {
        List<Doctor> doctors = v.doctors().stream().map(d -> new Doctor(d.doctorMembershipId(), d.doctorName(),
                d.nowWithDoctor() == null ? null : new NowWithDoctor(d.nowWithDoctor().visitId(),
                        d.nowWithDoctor().tokenNo(), d.nowWithDoctor().patientName(), d.nowWithDoctor().age(),
                        d.nowWithDoctor().sex(), d.nowWithDoctor().visitType()),
                d.nextTokens())).toList();
        List<Task> tasks = v.myTasks().stream().map(t -> new Task(t.visitId(), t.tokenNo(), t.patientName(),
                t.stage(), t.doctorName(), t.waitMinutes())).toList();
        return new TodaySummaryResponse(v.patientsTotal(), v.newCount(), v.followUpCount(), v.waitingCount(),
                v.inOpdCount(), v.longestWaitMinutes(), v.followUpsDue(), v.followUpsReturned(),
                v.followUpsMissed(), doctors, tasks);
    }
}
