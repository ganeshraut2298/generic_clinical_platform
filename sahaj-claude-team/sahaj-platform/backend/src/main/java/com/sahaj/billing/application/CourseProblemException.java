package com.sahaj.billing.application;

/**
 * A billing error whose problem type is one of the course contract's own slugs (docs/api/billing.yaml 0.2.0:
 * course-not-found, receipt-not-found, amount-over-balance). The billing error handler turns it into an RFC 9457
 * problem. The detail is fixed plain English written here; it never carries a name or an amount that was typed.
 */
public class CourseProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String slug;
    private final String title;
    private final String field;

    private CourseProblemException(int status, String slug, String title, String detail, String field) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
        this.field = field;
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

    /** The request field to point at, or null. */
    public String field() {
        return field;
    }

    public static CourseProblemException courseNotFound() {
        return new CourseProblemException(404, "course-not-found", "Course not found",
                "There is no such treatment course.", null);
    }

    public static CourseProblemException receiptNotFound() {
        return new CourseProblemException(404, "receipt-not-found", "Receipt not found",
                "There is no such receipt for this course.", null);
    }

    public static CourseProblemException amountOverBalance() {
        return new CourseProblemException(400, "amount-over-balance", "More than the balance",
                "The amount cannot be more than the balance on the course.", "amountPaise");
    }
}
