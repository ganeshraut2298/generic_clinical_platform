package com.sahaj.opd.application.emr;

/**
 * An EMR error whose problem type is one of the contract's own slugs (docs/api/opd.yaml 0.2.0): bad-request,
 * template-not-found, section-not-found, hidden-section-required, section-not-hideable. The opd error handler turns
 * it into an RFC 9457 problem. The detail is fixed plain English written here; it never carries what was typed.
 */
public class EmrProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String slug;
    private final String title;

    private EmrProblemException(int status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
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

    public static EmrProblemException badRequest(String detail) {
        return new EmrProblemException(400, "bad-request", "Bad request", detail);
    }

    public static EmrProblemException templateNotFound() {
        return new EmrProblemException(404, "template-not-found", "Template not found",
                "There is no EMR template for this specialty yet.");
    }

    public static EmrProblemException sectionNotFound() {
        return new EmrProblemException(404, "section-not-found", "Section not found",
                "That section is not in this template.");
    }

    public static EmrProblemException hiddenSectionRequired() {
        return new EmrProblemException(400, "hidden-section-required", "A hidden section cannot be required",
                "A hidden section cannot be required.");
    }

    public static EmrProblemException sectionNotHideable(String label) {
        return new EmrProblemException(400, "section-not-hideable", "This section cannot be hidden",
                label + " cannot be hidden.");
    }
}
