package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.SaleMath;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Prices and checks a cart (docs/api/pharmacy-sales.yaml pharmacyQuoteSale; ADR-030 S3, S4, S9). The one place where FEFO batches,
 * the GST maths of {@link SaleMath}, the allergy keyword match, the Schedule H1 and X marks and the discount decision meet, so the
 * quote and the bill save work the same cart the same way.
 *
 * <p>Without {@code lock} the AVAILABLE rows are read plainly and an expired row makes the store sweep run first (ADR-029 P4; the
 * caller is therefore in a read-write transaction). With {@code lock} every balance row of the cart's products is locked in
 * canonical order through {@link StockRepository#lockBalances} and FEFO is worked out in memory from the locked rows; the caller
 * then posts through {@link StockPosting}. Names, allergy text and quantities of an audited product (rule 32) are never logged.
 */
@Component
public class SalePricer {

    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final SaleSupport support;
    private final ApprovalRuleRepository rules;
    private final CustomerRepository customers;
    private final BlindCount blind;
    private final ActiveReasonService reasons;
    private final DiscountApprovals discountApprovals;
    private final PharmacyTime time;

    public SalePricer(ProductRepository products, StockRepository stock, StockPosting posting, SaleSupport support,
                      ApprovalRuleRepository rules, CustomerRepository customers, BlindCount blind,
                      ActiveReasonService reasons, DiscountApprovals discountApprovals, PharmacyTime time) {
        this.discountApprovals = discountApprovals;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.support = support;
        this.rules = rules;
        this.customers = customers;
        this.blind = blind;
        this.reasons = reasons;
        this.time = time;
    }

    /** The field checks of a cart; null when {@code errors} grew (the caller throws them together with its own). */
    public SaleCart.Cart read(UUID tenantId, SaleInputs.CartInput in, List<FieldMessage> errors) {
        return SaleCart.validate(in, time.today(), (list, key) -> reasons.isActive(tenantId, list, key), errors);
    }

    /**
     * What the cart needs that this release cannot give (rule 26): a registered patient or a link-pending bill needs Integrated
     * mode (409 integration-off); an e-prescription part needs the OPD port, which is not connected (409 not-connected).
     */
    public void requireReady(UUID tenantId, SaleCart.Cart cart) {
        if ("PATIENT".equals(cart.buyer().kind()) || cart.buyer().linkPending()) {
            support.requireIntegrated(tenantId);
        }
        if (cart.wantsRx()) {
            throw PharmacyProblemException.notConnected("Prescriptions are not connected. Bill the items typed in.");
        }
    }

    /** One priced batch of the cart: the line asked for, the batch picked, the quantity from it and its money. */
    public record PricedLine(int lineNo, int inputIndex, SaleCart.Line input, ProductRepository.Row product,
                             ProductRepository.Row prescribed, UUID batchId, String batchNo, LocalDate expiry, int qty,
                             BigDecimal mrp, BigDecimal gstRate, SaleMath.Line money) {
    }

    /** What the pricing found, for the quote and for the bill save. */
    public record Priced(SaleViews.SaleQuote quote, List<PricedLine> lines, SaleMath.Result math,
                         SaleSupport.PatientCard patient, boolean allergyNotChecked, CustomerRepository.Row customer,
                         List<FailingLine> shortLines, Integer firstAvailable, boolean anyH1, boolean anyX,
                         boolean anyScheduled, boolean anyDecision, Set<Integer> allergyBlocked, Set<Integer> h1Missing,
                         boolean prescriptionIncomplete, SaleRules.Discount discount, boolean canSellScheduled) {
    }

    /** The cart priced as of now. See the class comment for {@code lock}. */
    public Priced price(PharmacyCaller caller, Store store, SaleCart.Cart cart, boolean lock) {
        return price(caller, store, cart, lock, null);
    }

    /** As above; at the bill save {@code held} names the held bill of the save (the quote passes null: it takes none). */
    public Priced price(PharmacyCaller caller, Store store, SaleCart.Cart cart, boolean lock, DiscountApprovals.Held held) {
        UUID tenantId = caller.tenantId();
        UUID storeId = store.id();
        LocalDate today = time.today();
        boolean integrated = support.integrated(tenantId);

        Set<UUID> ids = new LinkedHashSet<>();
        for (SaleCart.Line line : cart.lines()) {
            ids.add(line.productId());
            if (line.prescribedProductId() != null) {
                ids.add(line.prescribedProductId());
            }
        }
        Map<UUID, ProductRepository.Full> found = products.findAll(tenantId, ids);
        for (UUID id : ids) {
            if (!found.containsKey(id)) {
                throw new NotFoundException(SaleSupport.NOT_FOUND);
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        for (int i = 0; i < cart.lines().size(); i++) {
            if (!found.get(cart.lines().get(i).productId()).active()) {
                errors.add(new FieldMessage("lines[" + i + "].productId", "This product is switched off."));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        Set<UUID> supply = new LinkedHashSet<>();
        cart.lines().forEach(l -> supply.add(l.productId()));
        Map<UUID, String> expiredBatch = new HashMap<>();
        Map<UUID, List<Bal>> shelf = loadShelf(tenantId, store, caller.actorId(), supply, lock, today, expiredBatch);

        // buyer context: the patient (allergy, age), the customer (credit, note)
        SaleCart.Buyer buyer = cart.buyer();
        SaleSupport.PatientCard patient = null;
        CustomerRepository.Row customer = null;
        SaleViews.AllergyInfo allergy;
        boolean allergyNotChecked = false;
        String allergyText = null;
        if ("PATIENT".equals(buyer.kind())) {
            patient = support.patient(tenantId, buyer.patientId(), false);
            java.util.Optional<com.sahaj.patient.api.PatientClinicalPort.PatientClinical> facts = support.clinical(tenantId,
                    patient.id());
            allergy = SaleSupport.allergyInfo(facts);
            allergyNotChecked = facts.isEmpty();
            allergyText = "RECORDED".equals(allergy.status()) ? allergy.text() : null;
            customer = customers.findByPatient(tenantId, patient.id()).orElse(null);
        } else if ("CUSTOMER".equals(buyer.kind())) {
            customer = customers.find(tenantId, buyer.customerId()).orElseThrow(() -> new NotFoundException(SaleSupport.NOT_FOUND));
            allergy = integrated ? SaleViews.AllergyInfo.of("NO_PATIENT", null)
                    : SaleViews.AllergyInfo.of("NOT_APPLICABLE", customer.allergyNote());
        } else {
            allergy = SaleViews.AllergyInfo.of(integrated ? "NO_PATIENT" : "NOT_APPLICABLE", null);
        }

        // FEFO, line by line, from one pool so that two lines of a product never take the same unit
        BlindCount.Mask mask = blind.at(caller, storeId);
        Map<UUID, Integer> remaining = new HashMap<>();
        Map<UUID, Bal> bals = new HashMap<>();
        shelf.values().forEach(rows -> rows.forEach(b -> {
            remaining.put(b.batchId(), b.qty());
            bals.put(b.batchId(), b);
        }));
        List<PricedLine> picked = new ArrayList<>();
        List<Object[]> shape = new ArrayList<>(); // per quote line: {PricedLine or null, inputIndex, shortBy, available, lastOfInput}
        List<FailingLine> shortLines = new ArrayList<>();
        Integer firstAvailable = null;
        List<SaleMath.LineIn> toPrice = new ArrayList<>();
        for (int i = 0; i < cart.lines().size(); i++) {
            SaleCart.Line line = cart.lines().get(i);
            ProductRepository.Row product = found.get(line.productId()).row();
            ProductRepository.Row prescribed = line.prescribedProductId() == null ? null
                    : found.get(line.prescribedProductId()).row();
            List<Fefo.Source> sources = new ArrayList<>();
            for (Bal b : shelf.getOrDefault(line.productId(), List.of())) {
                sources.add(new Fefo.Source(b.batchId(), b.expiryDate(), b.firstReceivedAt(), remaining.get(b.batchId())));
            }
            Fefo.Result result = Fefo.pick(sources, today, line.qty());
            if (result.shortBy() > 0) {
                shortLines.add(new FailingLine(null, i + 1, line.productId(), result.available()));
                if (firstAvailable == null) {
                    firstAvailable = result.available();
                }
            }
            if (result.picks().isEmpty()) {
                shape.add(new Object[] {null, i, result.shortBy(), result.available(), Boolean.TRUE, product, prescribed, line});
                continue;
            }
            for (int k = 0; k < result.picks().size(); k++) {
                Fefo.Pick pick = result.picks().get(k);
                remaining.merge(pick.batchId(), -pick.quantity(), Integer::sum);
                Bal b = bals.get(pick.batchId());
                PricedLine priced = new PricedLine(0, i, line, product, prescribed, b.batchId(), b.batchNo(), b.expiryDate(),
                        pick.quantity(), b.mrp(), product.gstRate(), null);
                picked.add(priced);
                toPrice.add(new SaleMath.LineIn(pick.quantity(), b.mrp(), product.gstRate()));
                boolean last = k == result.picks().size() - 1;
                shape.add(new Object[] {priced, i, last ? result.shortBy() : 0, result.available(), last, product, prescribed,
                        line});
            }
        }
        SaleMath.Result math = SaleMath.compute(toPrice, cart.percent());

        // quote lines, with the checks of each
        boolean canSellScheduled = StoreAccessGuard.holds(caller, storeId, StoreCapability.SELL_SCHEDULED);
        List<SaleViews.QuoteLine> quoteLines = new ArrayList<>();
        List<PricedLine> finalLines = new ArrayList<>();
        Set<Integer> allergyBlocked = new LinkedHashSet<>();
        Set<Integer> h1Missing = new LinkedHashSet<>();
        boolean anyH1 = false;
        boolean anyX = false;
        boolean anyScheduled = false;
        boolean anyDecision = false;
        List<String> versionKeys = new ArrayList<>();
        int pickedIndex = 0;
        int lineNo = 0;
        for (Object[] s : shape) {
            PricedLine p = (PricedLine) s[0];
            int inputIndex = (Integer) s[1];
            int shortBy = (Integer) s[2];
            int available = (Integer) s[3];
            ProductRepository.Row product = (ProductRepository.Row) s[5];
            ProductRepository.Row prescribed = (ProductRepository.Row) s[6];
            SaleCart.Line line = (SaleCart.Line) s[7];
            lineNo++;
            String schedule = product.schedule() == null ? "NONE" : product.schedule();
            boolean h1 = "H1".equals(schedule);
            boolean x = GoodsReceiptService.isScheduleX(product);
            boolean scheduled = h1 || x || "H".equals(schedule);
            anyH1 |= h1;
            anyX |= x;
            anyScheduled |= scheduled;
            anyDecision |= line.substituted() || line.allergyOverride() != null || line.h1Override() != null;

            SaleMath.Line money = p == null ? new SaleMath.Line(0, 0, 0, 0, 0) : math.lines().get(pickedIndex++);
            BigDecimal mrp = p == null ? stock.latestBatchMrp(tenantId, product.productId()).orElse(BigDecimal.ZERO) : p.mrp();
            PricedLine withMoney = p == null ? null : new PricedLine(lineNo, p.inputIndex(), p.input(), p.product(),
                    p.prescribed(), p.batchId(), p.batchNo(), p.expiry(), p.qty(), p.mrp(), p.gstRate(), money);
            if (withMoney != null) {
                finalLines.add(withMoney);
                versionKeys.add(product.productId() + "|" + p.batchId() + "|" + p.qty() + "|" + SaleRules.rate(p.mrp()));
            } else {
                versionKeys.add(product.productId() + "|none|0|short" + shortBy);
            }

            SaleViews.AllergyBlock block = null;
            if (allergyText != null && SaleRules.allergyMatches(allergyText, product.salt(), product.name())) {
                block = new SaleViews.AllergyBlock(allergyText, line.allergyOverride() != null);
                if (line.allergyOverride() == null) {
                    allergyBlocked.add(inputIndex);
                }
            }
            boolean prescriberMissing = h1 && line.h1Override() == null && prescriberIncomplete(cart.prescription());
            if (prescriberMissing) {
                h1Missing.add(inputIndex);
            }
            Integer days = null;
            if (p != null) {
                long d = ChronoUnit.DAYS.between(today, p.expiry());
                days = d <= 90 && d >= 0 ? (int) d : null;
            }
            boolean hidden = mask.hides(product.productId());
            boolean lastOfInput = (Boolean) s[4];
            quoteLines.add(new SaleViews.QuoteLine(lineNo, inputIndex, IndentService.productRef(product),
                    saltText(product), p == null ? null : new StockViews.BatchRef(p.batchId(), p.batchNo(), p.expiry()), true,
                    p == null ? 0 : p.qty(), hidden ? null : shortBy, hidden ? null : (lastOfInput || p == null ? available : null),
                    SaleRules.rate(mrp), SaleRules.percent(product.gstRate()), money.a(), money.d(), money.n(), money.t(),
                    money.x(), days, expiredBatch.get(product.productId()), schedule, scheduled, x, block, prescriberMissing,
                    line.h1Override() != null,
                    prescribed == null ? null : new SaleViews.SubstitutionMark(IndentService.productRef(prescribed)), "NONE"));
        }

        boolean prescriptionIncomplete = (anyH1 || anyX) && prescriptionIncomplete(cart, anyH1, anyX, patient, customer);
        SaleRules.Discount discount = SaleRules.discount(ruleSet(tenantId), cart.percent());
        // DEC-090: the OWNER is exempt from the discount rule; any request id the owner sends is ignored
        boolean ownerExempt = caller.isOwner();
        DiscountApprovals.Check approval = ownerExempt ? new DiscountApprovals.Check(DiscountApprovals.State.NONE, null, null)
                : discountApprovals.check(tenantId, storeId, cart, caller.actorId(), held);
        if (ownerExempt && discount.approvalNeeded()) {
            discount = new SaleRules.Discount(false, discount.limitPercent(), discount.limitNotSet(), discount.lean());
        } else if (discount.approvalNeeded() && approval.approved()) {
            discount = new SaleRules.Discount(false, discount.limitPercent(), discount.limitNotSet(), discount.lean());
        }
        SaleViews.BillSummary summary = summary(cart, math, toPrice);

        List<String> blockers = new ArrayList<>();
        if (cart.lines().isEmpty()) {
            blockers.add("NO_LINES");
        }
        if (!shortLines.isEmpty()) {
            blockers.add("SHORT");
        }
        if (!allergyBlocked.isEmpty()) {
            blockers.add("ALLERGY");
        }
        if (!h1Missing.isEmpty()) {
            blockers.add("H1_PRESCRIBER");
        }
        if (prescriptionIncomplete) {
            blockers.add("PRESCRIPTION");
        }
        if ((anyScheduled || anyDecision) && !canSellScheduled) {
            blockers.add("PHARMACIST");
        }
        if (discount.approvalNeeded()) {
            blockers.add("DISCOUNT_APPROVAL");
        }
        SaleViews.CreditLine credit = customer == null ? null : support.creditLine(caller, customer,
                customers.creditBillsSince(tenantId, List.of(customer.id()), support.sixtyDaysAgo())
                        .getOrDefault(customer.id(), 0L));
        SaleViews.DiscountInfo discountInfo = new SaleViews.DiscountInfo(discount.approvalNeeded(),
                SaleRules.percent(discount.limitPercent()), discount.limitNotSet(), discount.lean(),
                approval.state() == DiscountApprovals.State.NONE ? null : approval.state().name());
        SaleViews.SaleQuote quote = new SaleViews.SaleQuote(SaleRules.quoteVersion(versionKeys, cart.percent()), quoteLines,
                summary, discountInfo, allergy, credit, anyScheduled || anyDecision, anyX, anyH1 || anyX, anyH1,
                blockers.isEmpty(), blockers);
        return new Priced(quote, finalLines, math, patient, allergyNotChecked, customer, shortLines, firstAvailable, anyH1,
                anyX, anyScheduled, anyDecision, allergyBlocked, h1Missing, prescriptionIncomplete, discount,
                canSellScheduled);
    }

    // ---- stock ----

    private Map<UUID, List<Bal>> loadShelf(UUID tenantId, Store store, UUID me, Set<UUID> supply, boolean lock, LocalDate today,
                                           Map<UUID, String> expiredBatch) {
        Map<UUID, List<Bal>> out = new LinkedHashMap<>();
        supply.forEach(p -> out.put(p, new ArrayList<>()));
        if (lock) {
            for (Bal b : stock.lockBalances(tenantId, store.id(), supply)) {
                if (b.state() == StockState.AVAILABLE && b.qty() > 0) {
                    out.get(b.productId()).add(b);
                }
            }
            noteExpired(out, today, expiredBatch);
            return out;
        }
        for (UUID productId : supply) {
            out.put(productId, new ArrayList<>(stock.availableOfProduct(tenantId, store.id(), productId)));
        }
        noteExpired(out, today, expiredBatch);
        if (!expiredBatch.isEmpty()) {
            posting.sweepStore(tenantId, store.unitId(), store.id(), me);
            for (UUID productId : expiredBatch.keySet()) {
                out.put(productId, new ArrayList<>(stock.availableOfProduct(tenantId, store.id(), productId)));
            }
        }
        return out;
    }

    /** The batch number of the earliest expired AVAILABLE batch of each product, "<batch> expired · blocked". */
    private static void noteExpired(Map<UUID, List<Bal>> shelf, LocalDate today, Map<UUID, String> expiredBatch) {
        shelf.forEach((productId, rows) -> rows.stream().filter(b -> b.qty() > 0 && b.expiryDate().isBefore(today))
                .min(java.util.Comparator.comparing(Bal::expiryDate)).ifPresent(b -> expiredBatch.put(productId, b.batchNo())));
    }

    // ---- checks ----

    /** The H1 prescriber card is incomplete: no card, or no name, registration number or date. */
    static boolean prescriberIncomplete(SaleCart.Prescription p) {
        return p == null || p.prescriberName() == null || p.prescriberRegNo() == null || p.prescriptionDate() == null;
    }

    /**
     * The card "Prescription" is needed for an H1 or X line: it must exist; a Schedule X line needs the prescriber and the date (no
     * override); the patient's age comes from the patient or is typed; a typed patient name is needed without a patient; an H1 line
     * needs the patient's address. A prescriber missing on an H1 line only is answered by the H1 override, not by this.
     */
    private static boolean prescriptionIncomplete(SaleCart.Cart cart, boolean anyH1, boolean anyX,
                                                  SaleSupport.PatientCard patient, CustomerRepository.Row customer) {
        SaleCart.Prescription p = cart.prescription();
        if (p == null || "NONE".equals(cart.buyer().kind())) {
            return true;
        }
        if (anyX && prescriberIncomplete(p)) {
            return true;
        }
        if (patient == null && p.patientName() == null && cart.buyer().walkInName() == null
                && (customer == null || customer.name() == null)) {
            return true;
        }
        if (patient == null && p.patientAge() == null) {
            return true;
        }
        return anyH1 && p.patientAddress() == null;
    }

    private List<ApprovalRule> ruleSet(UUID tenantId) {
        ApprovalRuleRepository.Version version = rules.current(tenantId).orElse(null);
        return version == null || version.id() == null ? ApprovalPresets.defaults() : rules.rules(tenantId, version.id());
    }

    private static SaleViews.BillSummary summary(SaleCart.Cart cart, SaleMath.Result math, List<SaleMath.LineIn> priced) {
        List<SaleViews.TaxByRate> byRate = new ArrayList<>();
        for (SaleMath.RateTax r : math.byRate()) {
            byRate.add(new SaleViews.TaxByRate(SaleRules.percent(r.rate()), r.taxable(), r.cgst(), r.sgst(),
                    r.taxable() + r.tax()));
        }
        int units = priced.stream().mapToInt(SaleMath.LineIn::qty).sum();
        boolean hasDiscount = cart.percent() != null && cart.percent().signum() > 0;
        return new SaleViews.BillSummary(cart.lines().size(), units, math.gross(),
                hasDiscount ? SaleRules.percent(cart.percent()) : null, math.discount(), byRate, math.cgst(), math.sgst(),
                math.roundOff(), math.netPayable());
    }

    private static String saltText(ProductRepository.Row p) {
        StringBuilder text = new StringBuilder();
        if (p.salt() != null && !p.salt().isBlank()) {
            text.append(p.salt().trim());
        }
        if (p.strength() != null && !p.strength().isBlank()) {
            text.append(text.length() == 0 ? "" : " ").append(p.strength().trim());
        }
        if (p.form() != null && !p.form().isBlank()) {
            text.append(text.length() == 0 ? "" : " · ").append(p.form().trim());
        }
        if (p.purchaseUnit() != null && !p.purchaseUnit().isBlank()) {
            text.append(text.length() == 0 ? "" : " · ").append(p.purchaseUnit().trim()).append(" of ").append(p.conversion());
        }
        return text.toString();
    }
}
