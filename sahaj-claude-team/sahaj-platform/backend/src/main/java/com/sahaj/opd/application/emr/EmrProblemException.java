package com.sahaj.opd.application.emr;

/**
 * An EMR error whose problem type is one of the contract's own slugs (docs/api/opd.yaml 0.3.0). The opd error handler
 * turns it into an RFC 9457 problem. The detail is fixed plain English written here; it never carries what was typed,
 * a patient name or a clinical value.
 */
public class EmrProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String slug;
    private final String title;
    private java.util.List<com.sahaj.platform.api.InvalidFieldsException.FieldMessage> fieldErrors = java.util.List.of();
    private final java.util.Map<String, Object> extensions = new java.util.LinkedHashMap<>();

    private EmrProblemException(int status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    /** The field messages of a form-invalid or form-not-publishable answer (fixed words, never a typed value). */
    public java.util.List<com.sahaj.platform.api.InvalidFieldsException.FieldMessage> fieldErrors() {
        return fieldErrors;
    }

    public EmrProblemException withFieldErrors(
            java.util.List<com.sahaj.platform.api.InvalidFieldsException.FieldMessage> errors) {
        this.fieldErrors = java.util.List.copyOf(errors);
        return this;
    }

    /** Extra problem properties such as blockers, rows or holderName (staff names and fixed words, never patient data). */
    public java.util.Map<String, Object> extensions() {
        return extensions;
    }

    public EmrProblemException withExtension(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public int status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    // ---- 400 ----

    public static EmrProblemException badRequest(String detail) {
        return new EmrProblemException(400, "bad-request", "Bad request", detail);
    }

    public static EmrProblemException hiddenSectionRequired() {
        return new EmrProblemException(400, "hidden-section-required", "A hidden section cannot be required",
                "A hidden section cannot be required.");
    }

    public static EmrProblemException sectionNotHideable(String label) {
        return new EmrProblemException(400, "section-not-hideable", "This section cannot be hidden",
                label + " cannot be hidden.");
    }

    public static EmrProblemException sectionSetMismatch() {
        return new EmrProblemException(400, "section-set-mismatch", "The sections do not match",
                "Send every section of this department exactly once.");
    }

    public static EmrProblemException departmentNameTaken() {
        return new EmrProblemException(400, "department-name-taken", "Department name is taken",
                "A department with this name already exists.");
    }

    public static EmrProblemException departmentLimit() {
        return new EmrProblemException(400, "department-limit", "Department limit reached",
                "A clinic can add up to 50 departments.");
    }

    public static EmrProblemException visitTypeNotAvailable() {
        return new EmrProblemException(400, "visit-type-not-available", "Visit type not available",
                "Emergency is available in a later step.");
    }

    public static EmrProblemException notApplicable() {
        return new EmrProblemException(400, "not-applicable", "Not applicable",
                "This does not apply to this kind of record.");
    }

    // ---- 403 ----

    public static EmrProblemException notOwnPatient() {
        return new EmrProblemException(403, "not-own-patient", "Not your patient",
                "You can open only your own patients.");
    }

    public static EmrProblemException outsideScope() {
        return new EmrProblemException(403, "outside-scope", "Outside your scope",
                "This patient is not in your wards.");
    }

    // ---- 404 ----

    public static EmrProblemException patientNotFound() {
        return new EmrProblemException(404, "patient-not-found", "Patient not found",
                "There is no such patient in your clinic.");
    }

    public static EmrProblemException sectionNotFound() {
        return new EmrProblemException(404, "section-not-found", "Section not found",
                "That section is not in this department.");
    }

    public static EmrProblemException starterNotFound() {
        return new EmrProblemException(404, "starter-not-found", "Starter department not found",
                "There is no starter department with this key.");
    }

    public static EmrProblemException departmentNotFound() {
        return new EmrProblemException(404, "department-not-found", "Department not found",
                "There is no department with this key in your clinic.");
    }

    public static EmrProblemException departmentNotPublished() {
        return new EmrProblemException(404, "department-not-published", "Department not published",
                "This department has not been published yet.");
    }

    public static EmrProblemException versionNotFound() {
        return new EmrProblemException(404, "version-not-found", "Version not found",
                "This department has no such version.");
    }

    public static EmrProblemException recordNotFound() {
        return new EmrProblemException(404, "record-not-found", "EMR not found", "There is no such EMR.");
    }

    public static EmrProblemException encounterNotFound() {
        return new EmrProblemException(404, "encounter-not-found", "Encounter not found",
                "There is no such visit, admission or emergency case in your clinic.");
    }

    // ---- 409 ----

    public static EmrProblemException emrSetupChanged() {
        return new EmrProblemException(409, "emr-setup-changed", "Department changed",
                "Someone else changed this department. Reload and try again.");
    }

    public static EmrProblemException emrRecordChanged() {
        return new EmrProblemException(409, "emr-record-changed", "EMR changed",
                "This EMR was changed elsewhere. Reload to see the latest.");
    }

    public static EmrProblemException emrRecordSigned() {
        return new EmrProblemException(409, "emr-record-signed", "EMR already signed",
                "This EMR is signed and can no longer be changed.");
    }

    public static EmrProblemException encounterClosed() {
        return new EmrProblemException(409, "encounter-closed", "Encounter closed",
                "This admission or emergency case is closed and has no EMR to open.");
    }

    public static EmrProblemException ipdReadOnly() {
        return new EmrProblemException(409, "ipd-read-only", "IPD is read-only",
                "IPD is read-only. A new EMR cannot be started.");
    }

    // ---- phase 3 (ADR-022) ----

    /** A problem with one of the phase 3 slugs of docs/api/opd.yaml 0.4.0 (fixed words, never a typed value). */
    public static EmrProblemException of(int status, String slug, String title, String detail) {
        return new EmrProblemException(status, slug, title, detail);
    }

    public static EmrProblemException duplicateItem() {
        return of(400, "duplicate-item", "Already on the list", "This item is already on the list.");
    }

    public static EmrProblemException needsWhatsappService() {
        return of(400, "needs-whatsapp-service", "Needs the WhatsApp service",
                "Sending on WhatsApp needs the WhatsApp service.");
    }

    public static EmrProblemException pastVisitNotFound() {
        return of(404, "past-visit-not-found", "Past visit not found", "There is no such earlier visit.");
    }

    public static EmrProblemException sectionNotInLayout() {
        return of(409, "section-not-in-layout", "Section not in this EMR",
                "This EMR has no section for these items.");
    }

    public static EmrProblemException alreadyBilled() {
        return of(409, "already-billed", "Already billed", "Already billed. Ask billing to adjust.");
    }

    // ---- phase 5 (ADR-025) ----

    public static EmrProblemException alreadySignedBy(String doctorName) {
        return of(409, "emr-record-signed", "EMR already signed", "Already signed by " + doctorName + ".");
    }

    public static EmrProblemException emrRecordNotSigned() {
        return of(409, "emr-record-not-signed", "EMR not signed", "This visit is not signed yet.");
    }

    public static EmrProblemException emrRecordHeld(String holderName) {
        return of(409, "emr-record-held", "Open for editing by someone else", "Open for editing by " + holderName + ".")
                .withExtension("holderName", holderName);
    }

    public static EmrProblemException emrNotReady(java.util.List<?> blockers) {
        return of(409, "emr-not-ready", "Not ready to complete", "Check before completing.")
                .withExtension("blockers", blockers);
    }

    public static EmrProblemException caseClosed() {
        return of(409, "case-closed", "Patient is no longer in the queue", "This patient is no longer in the queue.");
    }

    public static EmrProblemException workflowMoveRefused() {
        return of(409, "workflow-move-refused", "The visit could not move", "The visit could not be moved to its next step. Try again.");
    }

    public static EmrProblemException ipdOrderNotPlaced(java.util.List<?> rows) {
        return of(409, "ipd-order-not-placed", "A medicine was not ordered", "A medicine could not be ordered. Check again.")
                .withExtension("rows", rows);
    }

    public static EmrProblemException opdDoctorNotAvailable() {
        return of(409, "opd-doctor-not-available", "You are not an OPD doctor", "You are not an active OPD doctor of this clinic.");
    }

    public static EmrProblemException idempotencyConflict() {
        return of(409, "idempotency-conflict", "Key already used", "This request key was already used for another request.");
    }

    public static EmrProblemException addendumLimit() {
        return of(400, "addendum-limit", "Addendum limit reached", "A record can have up to 50 addenda.");
    }

    public static EmrProblemException alreadyAdmitted(String ipNumber) {
        return of(409, "already-admitted", "Already admitted", "Already admitted · " + ipNumber)
                .withExtension("ipNumber", ipNumber);
    }
}
