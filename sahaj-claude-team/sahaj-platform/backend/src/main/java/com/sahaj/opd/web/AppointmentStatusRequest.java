package com.sahaj.opd.web;

/** The body of {@code PATCH /opd/appointments/{id}} (SAH-11): {@code status} is CANCELLED or NOSHOW. */
public record AppointmentStatusRequest(String status) {
}
