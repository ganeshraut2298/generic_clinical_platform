package com.sahaj.opd.application;

/** {@code BookingRules} in {@code docs/api/opd.yaml} (SAH-11), as submitted by the caller. */
public record BookingRulesInput(Integer closeBeforeHours, Integer remindBeforeHours, Integer noShowAfterMinutes,
                                 Boolean followPriority, Boolean selfBook, Boolean overbook) {
}
