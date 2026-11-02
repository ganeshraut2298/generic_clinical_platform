package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sahaj.platform.application.HolidayRepository.HolidayRow;
import java.time.LocalDate;
import java.util.UUID;

/** {@code Holiday} in {@code docs/api/platform.yaml} (SAH-7). {@code id} is set by the server. */
public record HolidayDto(@JsonProperty(access = JsonProperty.Access.READ_ONLY) UUID id, LocalDate date, String name,
                          String type) {

    public static HolidayDto from(HolidayRow row) {
        return new HolidayDto(row.id(), row.date(), row.name(), row.type());
    }
}
