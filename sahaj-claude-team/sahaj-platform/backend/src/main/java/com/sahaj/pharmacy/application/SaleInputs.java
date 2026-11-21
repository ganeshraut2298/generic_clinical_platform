package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The request bodies of docs/api/pharmacy-sales.yaml (SAH-80). Like {@link PharmacyInputs}: every member is nullable so the
 * service can answer all field errors together (rule 21) and nothing typed is echoed back. Whole numbers arrive as
 * {@link BigDecimal} so that 1.5 is refused instead of cut. The records are closed (an unknown field is 400) by the strict module.
 */
public final class SaleInputs {

    private SaleInputs() {
    }

    /** PUT /stores/{storeId}/bill-series. */
    public record StoreBillSeriesInput(Long version, String prefix, Boolean financialYearPart, BigDecimal sequenceLength,
                                       Boolean restartsEachFinancialYear) {
    }

    /** POST /stores/{storeId}/customers. */
    public record CustomerCreate(UUID clientKey, UUID patientId, String name, String phone, String allergyNote,
                                 BigDecimal creditLimitPaise, Boolean confirmSamePhone) {
    }

    /** PUT /stores/{storeId}/customers/{customerId}. */
    public record CustomerUpdate(BigDecimal version, String name, String phone, String allergyNote,
                                 BigDecimal creditLimitPaise, Boolean confirmSamePhone) {
    }

    /** POST .../customers/{customerId}/payments. */
    public record CustomerPaymentInput(UUID clientKey, BigDecimal amountPaise, String mode) {
    }

    /** ReasonInput: a key of the list the field names, and a note for a key ending in ".other". */
    public record ReasonInput(String reasonKey, String note) {
    }

    /** SubstitutionInput: reason list "substitution"; patientAgrees must be true. */
    public record SubstitutionInput(String reasonKey, String note, Boolean patientAgrees) {
    }

    /** BuyerInput: PATIENT, CUSTOMER, WALK_IN or NONE. */
    public record BuyerInput(String kind, UUID patientId, UUID customerId, String walkInName, String walkInPhone,
                             Boolean saveToCustomers, Boolean linkPending) {
    }

    /** One line of the cart: the product supplied and the whole units asked for; the server splits them by FEFO batch. */
    public record CartLineInput(UUID productId, BigDecimal qty, UUID prescribedProductId, SubstitutionInput substitution,
                                ReasonInput allergyOverride, ReasonInput h1Override, String rxLineId) {
    }

    /** The card "Prescription" (80-9). */
    public record PrescriptionInput(String source, String prescriberName, String prescriberRegNo, LocalDate prescriptionDate,
                                    String patientName, BigDecimal patientAge, String patientAddress) {
    }

    /** CartInput: what the quote prices. */
    public record CartInput(BuyerInput buyer, List<CartLineInput> lines, String discountPercent, UUID discountRequestId,
                            PrescriptionInput prescription, UUID rxId) {
    }

    /** One payment of a bill: a mode and whole paise. */
    public record PaymentInput(String mode, BigDecimal amountPaise) {
    }

    /** POST /stores/{storeId}/bills: the cart and what the screen saw. */
    public record BillCreate(UUID clientKey, String quoteVersion, BigDecimal expectedNetPaise, BuyerInput buyer,
                             List<CartLineInput> lines, String discountPercent, UUID discountRequestId,
                             PrescriptionInput prescription, UUID rxId, List<PaymentInput> payments, UUID heldBillId,
                             PharmacyInputs.SecondSignerInput secondSigner) {

        public CartInput cart() {
            return new CartInput(buyer, lines, discountPercent, discountRequestId, prescription, rxId);
        }
    }

    /** POST .../bills/{billId}/cancel. */
    public record CancelBillInput(UUID clientKey, BigDecimal version, String reasonKey, String note, Boolean backOnShelf,
                                  PharmacyInputs.SecondSignerInput secondSigner) {
    }

    /** One refund of a cancelled bill. */
    public record RefundLine(String mode, BigDecimal amountPaise) {
    }

    /** POST .../bills/{billId}/refund. */
    public record RefundInput(UUID clientKey, BigDecimal version, List<RefundLine> refunds) {
    }

    /** POST /stores/{storeId}/sale/discount-requests: the cart (with a percent above 0) and the held bill it belongs to. */
    public record DiscountRequestInput(UUID clientKey, UUID heldBillId, BuyerInput buyer, List<CartLineInput> lines,
                                       String discountPercent, UUID discountRequestId, PrescriptionInput prescription,
                                       UUID rxId) {

        public CartInput cart() {
            return new CartInput(buyer, lines, discountPercent, discountRequestId, prescription, rxId);
        }
    }

    /** One line of a sales return: the bill line, the whole units and the condition (list return-condition). */
    public record ReturnLineInput(UUID billLineId, BigDecimal qty, String conditionKey) {
    }

    /** One refund of a sales return: a mode and whole paise. */
    public record ReturnRefundInput(String mode, BigDecimal amountPaise) {
    }

    /** POST /stores/{storeId}/sales-returns. */
    public record SalesReturnCreate(UUID clientKey, UUID billId, List<ReturnLineInput> lines, String reasonKey, String note,
                                    List<ReturnRefundInput> refunds, PharmacyInputs.SecondSignerInput secondSigner) {
    }

    /** The pharmacist's decision on one doubtful line of a waiting return. */
    public record ReturnDecisionInput(UUID returnLineId, String decision) {
    }

    /** POST .../sales-returns/{returnId}/accept. */
    public record AcceptSalesReturnInput(UUID clientKey, BigDecimal version, List<ReturnDecisionInput> decisions,
                                         List<ReturnRefundInput> refunds, PharmacyInputs.SecondSignerInput secondSigner) {
    }

    /** POST /compliance/pharmacist-registrations. */
    public record PharmacistRegistrationCreate(UUID clientKey, UUID membershipId, String registrationNo, LocalDate validTill) {
    }

    /** PUT /compliance/pharmacist-registrations/{registrationId}. */
    public record PharmacistRegistrationUpdate(BigDecimal version, String registrationNo, LocalDate validTill) {
    }

    /** POST .../bills/{billId}/link-patient. */
    public record LinkPatientInput(BigDecimal version, UUID patientId) {
    }

    /** POST /stores/{storeId}/held-bills: the cart and the held bill it replaces, if any. */
    public record HoldInput(UUID clientKey, UUID replacesHeldBillId, BuyerInput buyer, List<CartLineInput> lines,
                            String discountPercent, UUID discountRequestId, PrescriptionInput prescription, UUID rxId) {

        public CartInput cart() {
            return new CartInput(buyer, lines, discountPercent, discountRequestId, prescription, rxId);
        }
    }
}
