package com.sahaj.opd.application;

import java.util.List;
import java.util.UUID;

/** {@code TodaySummary} in {@code docs/api/opd.yaml} (SAH-15). Names are for the screen only, never logged. */
public record TodaySummaryView(int patientsTotal, int newCount, int followUpCount, int waitingCount, int inOpdCount,
                                int longestWaitMinutes, int followUpsDue, int followUpsReturned, int followUpsMissed,
                                List<Doctor> doctors, List<Task> myTasks) {

    public record Doctor(UUID doctorMembershipId, String doctorName, NowWithDoctor nowWithDoctor,
                          List<Integer> nextTokens) {
    }

    public record NowWithDoctor(UUID visitId, int tokenNo, String patientName, int age, String sex,
                                 String visitType) {
    }

    public record Task(UUID visitId, int tokenNo, String patientName, String stage, String doctorName,
                        int waitMinutes) {
    }
}
