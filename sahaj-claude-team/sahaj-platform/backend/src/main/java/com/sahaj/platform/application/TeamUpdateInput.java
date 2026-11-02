package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;

/** Edit a team member (SAH-6): every field is optional; only the fields present change. */
public record TeamUpdateInput(String name, MembershipRole role, DoctorInput doctor) {
}
