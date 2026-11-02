package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.platform.application.TeamMemberView;
import java.time.Instant;
import java.util.UUID;

/** {@code TeamMember} in {@code docs/api/platform.yaml} (SAH-6). {@code id} is the membership id; the mobile is always masked. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberResponse(UUID id, String name, String mobileMasked, String role, String status,
                                  @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastLoginAt,
                                  DoctorDetails doctor) {

    public static TeamMemberResponse from(TeamMemberView view) {
        return new TeamMemberResponse(view.id(), view.name(), view.mobileMasked(), view.role().name(),
                view.status().name(), view.lastLoginAt(), DoctorDetails.from(view.doctor()));
    }
}
