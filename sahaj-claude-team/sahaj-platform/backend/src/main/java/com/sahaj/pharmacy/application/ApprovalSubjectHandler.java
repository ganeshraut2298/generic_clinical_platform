package com.sahaj.pharmacy.application;

import java.util.List;
import java.util.UUID;

/**
 * What a document type contributes to the approval engine (ADR-029 P6). Each later package registers one as a Spring bean; the
 * engine, the inbox and the decision endpoint never change when a type is added. A handler runs inside the decision's
 * transaction, after the request row, the decision and the status are written: a final approval lets the document proceed (a
 * stock adjustment, an audit variance and a write-off are posted here, with the approver on the ledger lines), a rejection
 * returns the document to its raiser. A refusal thrown here (for example 409 {@code insufficient-stock}) rolls the whole decision
 * back, so nothing is decided.
 */
public interface ApprovalSubjectHandler {

    /** What the inbox row and the request screen show about the document. */
    record Summary(String what, String chip, UUID subjectStoreId, List<String> actions) {
    }

    /** The decision as the handler sees it. {@code approver} is the deciding membership; {@code actingAs} its capacity. */
    record Decision(UUID tenantId, PharmacyCaller approver, ApprovalRepository.Request request, String actingAs,
                    PharmacyInputs.ApprovalDecisionInput input) {
    }

    /** The {@code subject_type} this handler serves, for example {@code STOCK_ADJUSTMENT}. */
    String subjectType();

    Summary summary(UUID tenantId, ApprovalRepository.Request request);

    /**
     * L18: whether the caller may read this document's approval request because of the document itself: the document's own read
     * capability at its store (the purchase capability for a purchase order, a receipt and a debit note; adjust stock for an
     * adjustment). The engine adds the current approver, the raiser, the owners and the people who decided; anyone else is
     * answered 404. The default is no, so a new document type is closed until its handler says who may read.
     */
    default boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return false;
    }

    /** Type-specific input rules (a 400 for a body the type does not accept); the engine has checked the common ones. */
    default void validateDecision(Decision decision) {
    }

    /** The final approval of the last level: the document proceeds. */
    void onApproved(Decision decision);

    /**
     * An approval of a level that is not the last (a second level applies). Most types do nothing; an indent saves the quantities
     * the approver set (SAH-79-10) so the next approver sees them.
     */
    default void onLevelApproved(Decision decision) {
    }

    /** A rejection at any level: the document returns to its raiser as rejected. */
    void onRejected(Decision decision);
}
