package com.sahaj.pharmacy.application;

import java.util.List;
import java.util.UUID;

/**
 * Port to the IPD module's admitted patients of a ward (ADR-029 P14, DEC-072 Q2): the patient-wise consumption entry of
 * story 79-16 reads who is admitted to a ward-linked store's ward. IPD has no public reader of this yet, so phase 1 plugs the
 * pharmacy-owned no-op {@link NoOpAdmittedPatientsReader}, which answers 409 {@code integration-off}. A later package replaces
 * it with an adapter over the IPD module's api package. The answer never carries a patient's name in a log.
 */
public interface AdmittedPatientsReader {

    /** One admitted patient of the ward: the admission id and the admission number shown. Never a name in a log. */
    record Admitted(UUID admissionId, String admissionNo, String patientName, String bed) {
    }

    /** One IPD ward a ward store can be linked to. */
    record Ward(UUID wardId, String name) {
    }

    /**
     * The IPD wards of the unit (SAH-79-16, GET /ipd-wards).
     *
     * @throws IntegrationOffException while no reader is connected (the no-op)
     */
    List<Ward> wards(UUID tenantId);

    /**
     * The patients admitted to the ward now.
     *
     * @throws IntegrationOffException while no reader is connected (the no-op)
     */
    List<Admitted> admittedTo(UUID tenantId, UUID wardId);
}
