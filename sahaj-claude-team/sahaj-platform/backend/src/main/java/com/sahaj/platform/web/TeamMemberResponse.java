package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.platform.application.TeamMemberView;
import java.time.Instant;
import java.util.UUID;

/** {@code TeamMember} in {@code docs/api/platform.yaml} (SAH-6). {@code id} is the membership id; the mobile is always masked. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberResponse(UUID id, String name, String mobileMasked, String role, String status,
                                  @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastLoginAt,
                                  DoctorDetails doctor, Pin pin) {

    /**
     * {@code TeamMemberPin} (SAH-31): state always, {@code setAt} and {@code lockedUntil} always present, null when not
     * applicable. SAH-38: {@code colleagueTries7d} always present, a number for an owner viewer and null otherwise.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Pin(String state, Instant setAt, Instant lockedUntil, Integer colleagueTries7d) {
    }

    public static TeamMemberResponse from(TeamMemberView view) {
        Pin pin = view.pin() == null ? null
                : new Pin(view.pin().state().name(), view.pin().setAt(), view.pin().lockedUntil(),
                        view.pin().colleagueTries7d());
        return new TeamMemberResponse(view.id(), view.name(), view.mobileMasked(), view.role().name(),
                view.status().name(), view.lastLoginAt(), DoctorDetails.from(view.doctor()), pin);
    }
}
