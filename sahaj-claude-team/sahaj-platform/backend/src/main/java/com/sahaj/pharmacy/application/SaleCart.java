package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.SaleInputs.BuyerInput;
import com.sahaj.pharmacy.application.SaleInputs.CartInput;
import com.sahaj.pharmacy.application.SaleInputs.CartLineInput;
import com.sahaj.pharmacy.application.SaleInputs.PrescriptionInput;
import com.sahaj.pharmacy.application.SaleInputs.ReasonInput;
import com.sahaj.pharmacy.application.SaleInputs.SubstitutionInput;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * A cart that passed the field checks of docs/api/pharmacy-sales.yaml CartInput (rule 30 limits, whole quantities, active reasons,
 * the buyer, the discount percent, the prescription card), in the typed form the pricer works with. {@link #validate} answers all
 * field errors together (rule 21) and never echoes what was typed. What needs the database (the product, the patient, the stock) is
 * checked later by {@link SalePricer}.
 */
final class SaleCart {

    static final int MAX_LINES = 100;
    static final int MAX_QTY = 999_999;
    /** The notes of a bill line are stored in varchar(200) columns (migration 1840), below the 300 of the contract. */
    static final int MAX_LINE_NOTE = 200;
    static final String ADD_ITEM = "Add at least one item.";
    static final String TOO_MANY = "A bill can have at most 100 items.";

    record Buyer(String kind, UUID patientId, UUID customerId, String walkInName, String walkInPhone,
                 boolean saveToCustomers, boolean linkPending) {
    }

    record Line(UUID productId, int qty, UUID prescribedProductId, SubstitutionInput substitution, ReasonInput allergyOverride,
                ReasonInput h1Override, String rxLineId) {

        boolean substituted() {
            return prescribedProductId != null && !prescribedProductId.equals(productId);
        }
    }

    record Prescription(String source, String prescriberName, String prescriberRegNo, LocalDate prescriptionDate,
                        String patientName, Integer patientAge, String patientAddress) {
    }

    record Cart(Buyer buyer, List<Line> lines, BigDecimal percent, UUID discountRequestId, Prescription prescription,
                UUID rxId) {

        /** The cart as the contract stores and returns it (whole quantities, the percent as text). */
        CartInput toInput() {
            List<CartLineInput> out = new ArrayList<>();
            for (Line l : lines) {
                out.add(new CartLineInput(l.productId(), BigDecimal.valueOf(l.qty()), l.prescribedProductId(), l.substitution(),
                        l.allergyOverride(), l.h1Override(), l.rxLineId()));
            }
            BuyerInput b = new BuyerInput(buyer.kind(), buyer.patientId(), buyer.customerId(), buyer.walkInName(),
                    buyer.walkInPhone(), buyer.saveToCustomers(), buyer.linkPending());
            PrescriptionInput p = prescription == null ? null
                    : new PrescriptionInput(prescription.source(), prescription.prescriberName(), prescription.prescriberRegNo(),
                            prescription.prescriptionDate(), prescription.patientName(),
                            prescription.patientAge() == null ? null : BigDecimal.valueOf(prescription.patientAge()),
                            prescription.patientAddress());
            return new CartInput(b, out, SaleRules.percent(percent), discountRequestId, p, rxId);
        }

        boolean wantsRx() {
            return rxId != null || (prescription != null && "E_RX".equals(prescription.source()))
                    || lines.stream().anyMatch(l -> l.rxLineId() != null);
        }
    }

    private SaleCart() {
    }

    /**
     * Checks the cart. {@code active} says whether a reason key is active in a list. Returns null when {@code errors} is not empty
     * after the call (the caller throws them together with its own).
     */
    static Cart validate(CartInput in, LocalDate today, BiPredicate<ReasonList, String> active, List<FieldMessage> errors) {
        int before = errors.size();
        Buyer buyer = buyer(in.buyer(), errors);
        List<Line> lines = lines(in.lines(), active, errors);
        BigDecimal percent = percent(in.discountPercent(), errors);
        Prescription prescription = prescription(in.prescription(), today, errors);
        if (errors.size() > before) {
            return null;
        }
        return new Cart(buyer, lines, percent, in.discountRequestId(), prescription, in.rxId());
    }

    private static Buyer buyer(BuyerInput in, List<FieldMessage> errors) {
        if (in == null || in.kind() == null) {
            errors.add(new FieldMessage("buyer.kind", "Choose who the bill is for"));
            return null;
        }
        String kind = in.kind();
        String name = SaleSupport.blank(in.walkInName());
        String phone = SaleSupport.blank(in.walkInPhone());
        switch (kind) {
            case "PATIENT":
                if (in.patientId() == null) {
                    errors.add(new FieldMessage("buyer.patientId", "Choose a patient"));
                }
                break;
            case "CUSTOMER":
                if (in.customerId() == null) {
                    errors.add(new FieldMessage("buyer.customerId", "Choose a customer"));
                }
                break;
            case "WALK_IN":
                if (name == null || name.length() > 80) {
                    errors.add(new FieldMessage("buyer.walkInName", "Enter the name"));
                }
                if (phone != null && !SaleRules.validPhone(phone)) {
                    errors.add(new FieldMessage("buyer.walkInPhone", "Enter a 10-digit phone number"));
                }
                break;
            case "NONE":
                break;
            default:
                errors.add(new FieldMessage("buyer.kind", "Choose who the bill is for"));
                return null;
        }
        boolean linkPending = Boolean.TRUE.equals(in.linkPending());
        if (linkPending && !"WALK_IN".equals(kind)) {
            errors.add(new FieldMessage("buyer.linkPending", "Link to patient pending is for a walk-in only"));
        }
        return new Buyer(kind, "PATIENT".equals(kind) ? in.patientId() : null, "CUSTOMER".equals(kind) ? in.customerId() : null,
                "WALK_IN".equals(kind) ? name : null, "WALK_IN".equals(kind) ? phone : null,
                Boolean.TRUE.equals(in.saveToCustomers()) && "WALK_IN".equals(kind), linkPending);
    }

    private static List<Line> lines(List<CartLineInput> in, BiPredicate<ReasonList, String> active, List<FieldMessage> errors) {
        List<Line> out = new ArrayList<>();
        if (in == null || in.isEmpty()) {
            errors.add(new FieldMessage("lines", ADD_ITEM));
            return out;
        }
        if (in.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", TOO_MANY));
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            CartLineInput l = in.get(i);
            String at = "lines[" + i + "]";
            if (l == null || l.productId() == null) {
                errors.add(new FieldMessage(at + ".productId", "Choose a product"));
                continue;
            }
            Integer qty = PharmacyParams.wholeNumber(l.qty(), 1, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".qty", "Enter a whole number from 1 to " + MAX_QTY));
                continue;
            }
            SubstitutionInput substitution = l.substitution();
            boolean differs = l.prescribedProductId() != null && !l.prescribedProductId().equals(l.productId());
            if (differs) {
                if (substitution == null || !Boolean.TRUE.equals(substitution.patientAgrees())
                        || !reasonOk(ReasonList.SUBSTITUTION, substitution.reasonKey(), substitution.note(), active)) {
                    errors.add(new FieldMessage(at + ".substitution", "Choose a reason and confirm the patient agrees."));
                }
            } else {
                substitution = null;
            }
            ReasonInput allergy = l.allergyOverride();
            if (allergy != null && !reasonOk(ReasonList.ALLERGY_OVERRIDE, allergy.reasonKey(), allergy.note(), active)) {
                errors.add(new FieldMessage(at + ".allergyOverride", "Choose a reason"));
            }
            ReasonInput h1 = l.h1Override();
            if (h1 != null && !reasonOk(ReasonList.H1_OVERRIDE, h1.reasonKey(), h1.note(), active)) {
                errors.add(new FieldMessage(at + ".h1Override", "Choose a reason"));
            }
            String rxLine = SaleSupport.blank(l.rxLineId());
            if (rxLine != null && rxLine.length() > 64) {
                errors.add(new FieldMessage(at + ".rxLineId", "Reload the page and try again."));
            }
            out.add(new Line(l.productId(), qty, differs ? l.prescribedProductId() : null, substitution, allergy, h1, rxLine));
        }
        return out;
    }

    /** An active key of the list; a key ending in ".other" needs a note of 4 to 200 characters. */
    static boolean reasonOk(ReasonList list, String key, String note, BiPredicate<ReasonList, String> active) {
        if (key == null || key.isBlank() || !active.test(list, key)) {
            return false;
        }
        String text = SaleSupport.blank(note);
        if (text != null && text.length() > MAX_LINE_NOTE) {
            return false;
        }
        return !key.endsWith(".other") || text != null && text.length() >= 4;
    }

    private static BigDecimal percent(String raw, List<FieldMessage> errors) {
        String text = SaleSupport.blank(raw);
        if (text == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0 && value.stripTrailingZeros().scale() <= 2) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // falls through to the field error
        }
        errors.add(new FieldMessage("discountPercent", "Enter a discount from 0 to 100%"));
        return BigDecimal.ZERO;
    }

    private static Prescription prescription(PrescriptionInput in, LocalDate today, List<FieldMessage> errors) {
        if (in == null) {
            return null;
        }
        if (!"E_RX".equals(in.source()) && !"TYPED".equals(in.source())) {
            errors.add(new FieldMessage("prescription.source", "Choose how the prescription was given"));
        }
        String doctor = SaleSupport.blank(in.prescriberName());
        String reg = SaleSupport.blank(in.prescriberRegNo());
        String patient = SaleSupport.blank(in.patientName());
        String address = SaleSupport.blank(in.patientAddress());
        if (doctor != null && doctor.length() > 80) {
            errors.add(new FieldMessage("prescription.prescriberName", "Keep the name to 80 characters"));
        }
        if (reg != null && reg.length() > 30) {
            errors.add(new FieldMessage("prescription.prescriberRegNo", "Keep the number to 30 characters"));
        }
        if (patient != null && patient.length() > 80) {
            errors.add(new FieldMessage("prescription.patientName", "Keep the name to 80 characters"));
        }
        if (address != null && address.length() > 200) {
            errors.add(new FieldMessage("prescription.patientAddress", "Keep the address to 200 characters"));
        }
        if (in.prescriptionDate() != null && in.prescriptionDate().isAfter(today)) {
            errors.add(new FieldMessage("prescription.prescriptionDate", "The prescription date cannot be in the future"));
        }
        Integer age = null;
        if (in.patientAge() != null) {
            age = PharmacyParams.wholeNumber(in.patientAge(), 0, 130);
            if (age == null) {
                errors.add(new FieldMessage("prescription.patientAge", "Enter an age from 0 to 130"));
            }
        }
        return new Prescription(in.source(), doctor, reg, in.prescriptionDate(), patient, age, address);
    }
}
