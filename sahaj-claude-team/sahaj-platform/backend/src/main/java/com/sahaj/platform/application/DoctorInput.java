package com.sahaj.platform.application;

/**
 * Doctor details on Add staff and Edit (SAH-6, {@code DoctorDetails} in {@code docs/api/platform.yaml}).
 * A null field means "not given"; on an update that leaves the stored value as it is, and a blank
 * text clears it.
 */
public record DoctorInput(String specialty, String degree, String registrationNo, Integer feeRupees) {
}
