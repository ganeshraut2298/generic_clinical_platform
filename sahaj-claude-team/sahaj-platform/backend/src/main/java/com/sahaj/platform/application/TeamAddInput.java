package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;

/** Add staff (SAH-6): name, mobile and role, plus doctor details for the two doctor roles. */
public record TeamAddInput(String name, String mobile, MembershipRole role, DoctorInput doctor) {
}
