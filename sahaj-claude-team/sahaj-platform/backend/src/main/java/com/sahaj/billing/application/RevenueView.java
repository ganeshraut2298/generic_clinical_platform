package com.sahaj.billing.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The revenue report (SAH-16) for one Asia/Kolkata range. Money is integer paise; names and mobiles are for the screen only. */
public record RevenueView(String range, long collectedPaise, Integer collectedChangePercent, long billedPaise,
                          int patients, long averageBillPaise, long duePaise, long atCounterPaise,
                          long discountPaise, long refundPaise, long cashInDrawerPaise, List<Day> days,
                          List<Mode> modes, List<Doctor> doctors, List<Service> topServices, List<Due> dues,
                          List<Adjustment> adjustments) {

    public record Day(LocalDate date, long collectedPaise) {
    }

    public record Mode(String mode, long amountPaise) {
    }

    public record Doctor(UUID doctorMembershipId, String doctorName, int patients, long collectedPaise) {
    }

    public record Service(String name, long billedPaise) {
    }

    public record Due(UUID visitId, String patientName, String mobile, String doctorName, LocalDate date,
                      long balancePaise) {
    }

    public record Adjustment(String kind, String patientName, long amountPaise, String detail, LocalDate date) {
    }
}
