package com.sahaj.hra.web;

/** Body of {@code PUT /attendance/{membershipId}}: status is in, late or abs. */
public record AttendanceMarkRequest(String status) {
}
