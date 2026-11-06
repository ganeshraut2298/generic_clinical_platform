package com.sahaj.billing.web;

import com.sahaj.billing.application.RevenueView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Body of {@code GET /billing/revenue} (SAH-16), field for field the {@code RevenueReport} schema. */
public record RevenueResponse(String range, long collectedPaise, Integer collectedChangePercent, long billedPaise,
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

    static RevenueResponse from(RevenueView v) {
        return new RevenueResponse(v.range(), v.collectedPaise(), v.collectedChangePercent(), v.billedPaise(),
                v.patients(), v.averageBillPaise(), v.duePaise(), v.atCounterPaise(), v.discountPaise(),
                v.refundPaise(), v.cashInDrawerPaise(),
                v.days().stream().map(d -> new Day(d.date(), d.collectedPaise())).toList(),
                v.modes().stream().map(m -> new Mode(m.mode(), m.amountPaise())).toList(),
                v.doctors().stream().map(d -> new Doctor(d.doctorMembershipId(), d.doctorName(), d.patients(),
                        d.collectedPaise())).toList(),
                v.topServices().stream().map(s -> new Service(s.name(), s.billedPaise())).toList(),
                v.dues().stream().map(d -> new Due(d.visitId(), d.patientName(), d.mobile(), d.doctorName(),
                        d.date(), d.balancePaise())).toList(),
                v.adjustments().stream().map(a -> new Adjustment(a.kind(), a.patientName(), a.amountPaise(),
                        a.detail(), a.date())).toList());
    }
}
