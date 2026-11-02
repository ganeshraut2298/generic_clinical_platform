package com.sahaj.opd.web;

import com.sahaj.opd.application.BookingRulesInput;
import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;

/** {@code BookingRules} in {@code docs/api/opd.yaml} (SAH-11), for the request and the response. */
public record BookingRulesDto(Integer closeBeforeHours, Integer remindBeforeHours, Integer noShowAfterMinutes,
                               Boolean followPriority, Boolean selfBook, Boolean overbook) {

    public static BookingRulesDto from(BookingRulesRow row) {
        return new BookingRulesDto(row.closeBeforeHours(), row.remindBeforeHours(), row.noShowAfterMinutes(),
                row.followPriority(), row.selfBook(), row.overbook());
    }

    public BookingRulesInput toInput() {
        return new BookingRulesInput(closeBeforeHours, remindBeforeHours, noShowAfterMinutes, followPriority,
                selfBook, overbook);
    }
}
