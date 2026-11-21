package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.pharmacy.domain.ApprovalCondition;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-3: the counter's search and the quote (FEFO batches, GST, checks, discount, blind count). */
class SaleQuoteServiceTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);
    private static final LocalDate NEAR = LocalDate.of(2026, 12, 31);

    private final SaleWorld w = new SaleWorld();
    private final SaleQuoteService service = new SaleQuoteService(w.f.gate, w.f.guard, w.products, w.shelfRepository,
            w.posting, w.pricer, w.blind, w.f.time);
    private final String shop = w.shop.id().toString();

    private SaleViews.SaleQuote quote(PharmacyFixture.Person who, SaleInputs.CartInput cart) {
        return service.quote(SaleWorld.TENANT, who.userId(), shop, SaleWorld.body(cart));
    }

    @Test
    void anOrderIsSplitAcrossBatchesEarliestExpiryFirstAndPricedByTheWorkedExample() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        UUID near = w.shelf(p, "B-NEAR", NEAR, 3, "280.20");
        w.shelf(p, "B-FAR", FAR, 10, "280.20");

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(w.walkIn(), "5", w.line(p, 2)));

        assertThat(q.lines()).hasSize(1);
        assertThat(q.lines().get(0).batch().batchNo()).isEqualTo("B-NEAR");
        assertThat(q.lines().get(0).batch().batchId()).isEqualTo(near);
        SaleViews.BillSummary s = q.summary();
        assertThat(s.mrpTotalPaise()).isEqualTo(56_040L);
        assertThat(s.discountPaise()).isEqualTo(2_802L);
        assertThat(s.taxByRate()).hasSize(1);
        assertThat(s.taxByRate().get(0).gstRate()).isEqualTo("12");
        assertThat(s.taxByRate().get(0).taxablePaise()).isEqualTo(47_534L);
        assertThat(s.cgstPaise()).isEqualTo(2_852L);
        assertThat(s.sgstPaise()).isEqualTo(2_852L);
        assertThat(s.roundOffPaise()).isEqualTo(-38L);
        assertThat(s.netPayablePaise()).isEqualTo(53_200L);
        assertThat(s.discountPercent()).isEqualTo("5");
        assertThat(q.canSave()).isFalse(); // the default rule has no discount limit set
        assertThat(q.discount().limitNotSet()).isTrue();
    }

    @Test
    void aQuantityAboveTheNearestBatchTakesTheRestFromTheNextOne() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-NEAR", NEAR, 3, "100.00");
        w.shelf(p, "B-FAR", FAR, 10, "110.00");

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 5)));

        assertThat(q.lines()).extracting(l -> l.batch().batchNo()).containsExactly("B-NEAR", "B-FAR");
        assertThat(q.lines()).extracting(SaleViews.QuoteLine::qty).containsExactly(3, 2);
        assertThat(q.lines()).extracting(SaleViews.QuoteLine::mrp).containsExactly("100.00", "110.00");
        assertThat(q.lines()).extracting(SaleViews.QuoteLine::inputIndex).containsExactly(0, 0);
        assertThat(q.summary().mrpTotalPaise()).isEqualTo(52_000L);
        assertThat(q.summary().unitCount()).isEqualTo(5);
        assertThat(q.canSave()).isTrue();
        assertThat(q.blockers()).isEmpty();
        assertThat(q.quoteVersion()).startsWith("q-");
    }

    @Test
    void twoLinesOfOneProductNeverTakeTheSameUnit() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-ONLY", FAR, 5, "10.00");

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 3), w.line(p, 3)));

        assertThat(q.lines()).extracting(SaleViews.QuoteLine::qty).containsExactly(3, 2);
        assertThat(q.lines().get(1).shortBy()).isEqualTo(1);
        assertThat(q.blockers()).containsExactly("SHORT");
    }

    @Test
    void aShortLineSaysHowManyAreInStockAndNothingAvailableIsALineWithoutABatch() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        UUID none = w.product("Emptycap", "Nothing", "NONE");
        w.shelf(p, "B-ONLY", FAR, 13, "10.00");

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 20), w.line(none, 1)));

        SaleViews.QuoteLine partly = q.lines().get(0);
        assertThat(partly.qty()).isEqualTo(13);
        assertThat(partly.shortBy()).isEqualTo(7);
        assertThat(partly.availableQty()).isEqualTo(13);
        SaleViews.QuoteLine empty = q.lines().get(1);
        assertThat(empty.batch()).isNull();
        assertThat(empty.qty()).isZero();
        assertThat(empty.shortBy()).isEqualTo(1);
        assertThat(empty.availableQty()).isZero();
        assertThat(q.canSave()).isFalse();
        assertThat(q.blockers()).contains("SHORT");
    }

    @Test
    void anExpiredBatchIsNamedAsBlockedAndTheStoreSweepRunsBeforeThePick() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-OLD", LocalDate.of(2026, 11, 1), 4, "10.00");
        w.shelf(p, "B-OK", FAR, 10, "10.00");
        doAnswer(inv -> {
            w.balances.get(p).removeIf(b -> b.expiryDate().isBefore(SaleWorld.TODAY));
            return 1;
        }).when(w.posting).sweepStore(any(), any(), any(), any());

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 2)));

        verify(w.posting).sweepStore(eq(SaleWorld.TENANT), any(), eq(w.shop.id()), eq(w.cashier.id()));
        assertThat(q.lines().get(0).batch().batchNo()).isEqualTo("B-OK");
        assertThat(q.lines().get(0).expiredBatchBlocked()).isEqualTo("B-OLD");
    }

    @Test
    void aBatchExpiringSoonShowsHowManyDaysAreLeftAndNoSweepRunsWhenNothingExpired() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-SOON", SaleWorld.TODAY.plusDays(30), 4, "10.00");

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 1)));

        assertThat(q.lines().get(0).expiresInDays()).isEqualTo(30);
        verify(w.posting, never()).sweepStore(any(), any(), any(), any());
    }

    @Test
    void theDiscountLimitIsReadFromTheRuleAndALeanRuleAppliesAtOnce() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-OK", FAR, 10, "100.00");
        ApprovalRule ten = new ApprovalRule(TransactionType.SALE_DISCOUNT, List.of(new ApprovalLevel(1, ApproverKind.OWNER,
                ApprovalCondition.abovePercent(new BigDecimal("10")))), null, null);
        ApprovalRuleRepository.Version v = new ApprovalRuleRepository.Version(UUID.randomUUID(), 1,
                com.sahaj.pharmacy.domain.RulePreset.STANDARD, null, java.time.Instant.now(), UUID.randomUUID());
        when(w.rules.current(any())).thenReturn(Optional.of(v));
        when(w.rules.rules(any(), eq(v.id()))).thenReturn(List.of(ten));

        SaleViews.SaleQuote five = quote(w.cashier, w.cart(w.walkIn(), "5", w.line(p, 1)));
        SaleViews.SaleQuote twelve = quote(w.cashier, w.cart(w.walkIn(), "12", w.line(p, 1)));

        assertThat(five.discount().approvalNeeded()).isFalse();
        assertThat(five.discount().limitPercent()).isEqualTo("10");
        assertThat(five.canSave()).isTrue();
        assertThat(twelve.discount().approvalNeeded()).isTrue();
        assertThat(twelve.blockers()).containsExactly("DISCOUNT_APPROVAL");
        assertThat(twelve.discount().approval()).isNull();

        when(w.rules.rules(any(), eq(v.id()))).thenReturn(List.of());
        assertThat(quote(w.cashier, w.cart(w.walkIn(), "40", w.line(p, 1))).discount().lean()).isTrue();
    }

    @Test
    void theOwnerIsExemptFromTheDiscountRuleAndAnyRequestIdHeSendsIsIgnored() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-OK", FAR, 10, "100.00");
        ApprovalRule ten = new ApprovalRule(TransactionType.SALE_DISCOUNT, List.of(new ApprovalLevel(1, ApproverKind.OWNER,
                ApprovalCondition.abovePercent(new BigDecimal("10")))), null, null);
        ApprovalRuleRepository.Version v = new ApprovalRuleRepository.Version(UUID.randomUUID(), 1,
                com.sahaj.pharmacy.domain.RulePreset.STANDARD, null, java.time.Instant.now(), UUID.randomUUID());
        when(w.rules.current(any())).thenReturn(Optional.of(v));
        when(w.rules.rules(any(), eq(v.id()))).thenReturn(List.of(ten));

        SaleViews.SaleQuote owner = quote(w.f.owner, w.cart(w.walkIn(), "40", w.line(p, 1)));
        SaleViews.SaleQuote cashier = quote(w.cashier, w.cart(w.walkIn(), "40", w.line(p, 1)));

        assertThat(owner.discount().approvalNeeded()).isFalse();
        assertThat(owner.blockers()).doesNotContain("DISCOUNT_APPROVAL");
        assertThat(owner.discount().approval()).isNull();
        assertThat(cashier.discount().approvalNeeded()).isTrue();
    }

    @Test
    void aScheduledLineNeedsAPharmacistAndACashierOrTheOwnerIsBlocked() {
        UUID h = w.product("Testcillin", "Amoxicillin", "H");
        UUID x = w.product("Testdone", "Methadone", "X");
        w.shelf(h, "B-H", FAR, 10, "10.00");
        w.shelf(x, "B-X", FAR, 10, "10.00");

        SaleViews.SaleQuote cashierQuote = quote(w.cashier, w.cart(w.walkIn(), null, w.line(h, 1)));
        SaleViews.SaleQuote pharmacistQuote = quote(w.f.pharmacist, w.cart(w.walkIn(), null, w.line(h, 1)));
        SaleViews.SaleQuote ownerQuote = quote(w.f.owner, w.cart(w.walkIn(), null, w.line(h, 1)));
        SaleViews.SaleQuote xQuote = quote(w.f.pharmacist, w.cart(w.walkIn(), null, w.line(x, 1)));

        assertThat(cashierQuote.needsPharmacist()).isTrue();
        assertThat(cashierQuote.blockers()).containsExactly("PHARMACIST");
        assertThat(ownerQuote.blockers()).containsExactly("PHARMACIST");
        assertThat(pharmacistQuote.blockers()).isEmpty();
        assertThat(pharmacistQuote.lines().get(0).needsPharmacist()).isTrue();
        assertThat(xQuote.needsSecondSigner()).isTrue();
        assertThat(xQuote.needsPrescription()).isTrue();
        assertThat(xQuote.blockers()).containsExactly("PRESCRIPTION");
    }

    @Test
    void anH1LineWithoutAPrescriberIsMarkedAndAPharmacistOverrideOrACardClearsIt() {
        UUID p = w.product("Testtuss", "Codeine", "H1");
        w.shelf(p, "B-H1", FAR, 10, "10.00");
        SaleInputs.PrescriptionInput card = new SaleInputs.PrescriptionInput("TYPED", "Dr Test One", "MH-1234",
                SaleWorld.TODAY.minusDays(1), "Test Walk-in One", BigDecimal.valueOf(40), "Test Lane 1");
        SaleInputs.CartLineInput overridden = new SaleInputs.CartLineInput(p, BigDecimal.ONE, null, null, null,
                new SaleInputs.ReasonInput("h1-override.confirmed", null), null);

        SaleViews.SaleQuote missing = quote(w.f.pharmacist, w.cart(w.walkIn(), null, w.line(p, 1)));
        SaleViews.SaleQuote withCard = quote(w.f.pharmacist, new SaleInputs.CartInput(w.walkIn(), List.of(w.line(p, 1)), null,
                null, card, null));
        SaleViews.SaleQuote withOverride = quote(w.f.pharmacist, new SaleInputs.CartInput(w.walkIn(), List.of(overridden), null,
                null, new SaleInputs.PrescriptionInput("TYPED", null, null, null, "Test Walk-in One", BigDecimal.valueOf(40),
                        "Test Lane 1"), null));

        assertThat(missing.lines().get(0).h1PrescriberMissing()).isTrue();
        assertThat(missing.needsH1PatientDetails()).isTrue();
        assertThat(missing.blockers()).containsExactly("H1_PRESCRIBER", "PRESCRIPTION");
        assertThat(withCard.blockers()).isEmpty();
        assertThat(withCard.canSave()).isTrue();
        assertThat(withOverride.lines().get(0).h1Overridden()).isTrue();
        assertThat(withOverride.blockers()).isEmpty();
    }

    @Test
    void aRecordedAllergyBlocksTheMatchingLineUntilAPharmacistOverridesIt() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientClinical.find(any(), eq(patientId))).thenReturn(Optional.of(new PatientClinicalPort.PatientClinical(
                patientId, "UHID-1", "Test Patient One", 34, "F", "Penicillin")));
        UUID p = w.product("Pen-Test", "Penicillin V", "NONE");
        UUID safe = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-PEN", FAR, 10, "10.00");
        w.shelf(safe, "B-PARA", FAR, 10, "10.00");
        SaleInputs.BuyerInput buyer = new SaleInputs.BuyerInput("PATIENT", patientId, null, null, null, false, false);
        SaleInputs.CartLineInput override = new SaleInputs.CartLineInput(p, BigDecimal.ONE, null, null,
                new SaleInputs.ReasonInput("allergy-override.confirmed", null), null, null);

        SaleViews.SaleQuote blocked = quote(w.cashier, w.cart(buyer, null, w.line(p, 1), w.line(safe, 1)));
        SaleViews.SaleQuote overridden = quote(w.f.pharmacist, new SaleInputs.CartInput(buyer, List.of(override), null, null,
                null, null));

        assertThat(blocked.allergy().status()).isEqualTo("RECORDED");
        assertThat(blocked.allergy().classesNotChecked()).isTrue();
        assertThat(blocked.lines().get(0).allergyBlock().allergyText()).isEqualTo("Penicillin");
        assertThat(blocked.lines().get(0).allergyBlock().overridden()).isFalse();
        assertThat(blocked.lines().get(1).allergyBlock()).isNull();
        assertThat(blocked.blockers()).containsExactly("ALLERGY");
        assertThat(overridden.lines().get(0).allergyBlock().overridden()).isTrue();
        assertThat(overridden.blockers()).isEmpty();
    }

    @Test
    void aFailedAllergyReadIsAllergyNotCheckedAndTheSaleGoesOn() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientClinical.find(any(), eq(patientId))).thenThrow(new IllegalStateException("down"));
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-PARA", FAR, 10, "10.00");

        SaleViews.SaleQuote q = quote(w.cashier, w.cart(new SaleInputs.BuyerInput("PATIENT", patientId, null, null, null, false,
                false), null, w.line(p, 1)));

        assertThat(q.allergy().status()).isEqualTo("NOT_CHECKED");
        assertThat(q.canSave()).isTrue();
    }

    @Test
    void aBlindCallerGetsNoQuantityOfAnAuditedProductButKeepsTheBatchesOfTheCart() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-ONLY", FAR, 3, "10.00");
        when(w.audits.openAuditProducts(any(), eq(w.shop.id()))).thenReturn(Set.of(p));

        SaleViews.SaleQuote blind = quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 5)));
        SaleViews.SaleQuote sees = quote(w.f.owner, w.cart(w.walkIn(), null, w.line(p, 5)));

        assertThat(blind.lines().get(0).qty()).isEqualTo(3);
        assertThat(blind.lines().get(0).shortBy()).isNull();
        assertThat(blind.lines().get(0).availableQty()).isNull();
        assertThat(blind.blockers()).contains("SHORT");
        assertThat(sees.lines().get(0).shortBy()).isEqualTo(2);
        assertThat(sees.lines().get(0).availableQty()).isEqualTo(3);
    }

    @Test
    void aRegisteredPatientNeedsIntegratedModeAndAnEPrescriptionIsNotConnected() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-OK", FAR, 10, "10.00");
        SaleInputs.CartLineInput rxLine = new SaleInputs.CartLineInput(p, BigDecimal.ONE, null, null, null, null, "rx-line-1");

        assertThatThrownBy(() -> quote(w.cashier, new SaleInputs.CartInput(w.walkIn(), List.of(rxLine), null, null, null, null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("not-connected");
                });
        w.f.unitRepository.updateSettings(SaleWorld.TENANT, w.unitId(), 0L, null, null, null, IntegrationMode.STANDALONE,
                w.f.owner.userId());
        assertThatThrownBy(() -> quote(w.cashier, w.cart(new SaleInputs.BuyerInput("PATIENT", UUID.randomUUID(), null, null, null,
                false, false), null, w.line(p, 1)))).isInstanceOf(IntegrationOffException.class);
    }

    @Test
    void aMalformedCartAnUnknownProductAndASwitchedOffProductAreRefused() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        assertThatThrownBy(() -> quote(w.cashier, w.cart(w.walkIn(), null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("Add at least one item."));
        assertThatThrownBy(() -> quote(w.cashier, w.cart(w.walkIn(), null, w.line(UUID.randomUUID(), 1))))
                .isInstanceOf(NotFoundException.class);

        ProductRepository.Full active = w.productRows.get(p);
        w.productRows.put(p, new ProductRepository.Full(active.row(), true, false, null, true, null, true, 0L, null));
        assertThatThrownBy(() -> quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 1)))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors().get(0).field()).isEqualTo("lines[0].productId"));
    }

    @Test
    void theQuoteWritesNothingButTheSweepAndTakesNoLock() {
        UUID p = w.product("Testamol", "Paracetamol", "NONE");
        w.shelf(p, "B-OK", FAR, 10, "10.00");

        quote(w.cashier, w.cart(w.walkIn(), null, w.line(p, 1)));

        verify(w.stock, never()).lockBalances(any(), any(), any());
        verify(w.posting, never()).post(any(), any());
        verify(w.bills, never()).insertBill(any(), any());
        assertThat(w.f.audit).isEmpty();
    }

    // ---- search ----

    private ProductRepository.Row row(UUID id, String name, String salt) {
        return w.productRows.get(id).row();
    }

    private void found(UUID... ids) {
        List<ProductRepository.Row> rows = new java.util.ArrayList<>();
        for (UUID id : ids) {
            rows.add(row(id, null, null));
        }
        when(w.products.search(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new ProductRepository.Slice<>(rows, rows.size()));
    }

    @Test
    void theSearchListsInStockFirstThenByNameWithTheFirstBatchMrpAndTheSaltChip() {
        UUID a = w.product("Zeta Test", "Paracetamol", "NONE");
        UUID b = w.product("Alpha Test", "Paracetamol", "NONE");
        UUID c = w.product("Para Test", "Other salt", "NONE");
        found(c, b, a);
        when(w.shelfRepository.shelf(any(), eq(w.shop.id()), any(), any())).thenReturn(Map.of(
                a, new SaleSearchRepository.Shelf(8, new BigDecimal("46.5")),
                c, new SaleSearchRepository.Shelf(2, new BigDecimal("10"))));

        SaleViews.SaleProductList list = service.search(SaleWorld.TENANT, w.cashier.userId(), shop, "para", null);

        assertThat(list.items()).extracting(r -> r.product().name()).containsExactly("Para Test", "Zeta Test", "Alpha Test");
        assertThat(list.items().get(1).availableQty()).isEqualTo(8);
        assertThat(list.items().get(1).mrpFrom()).isEqualTo("46.50");
        assertThat(list.items().get(1).matchedOnSalt()).isTrue();
        assertThat(list.items().get(0).matchedOnSalt()).isFalse();
        assertThat(list.items().get(2).outOfStock()).isTrue();
        assertThat(list.items().get(2).mrpFrom()).isNull();
        assertThat(list.items().get(0).packText()).isEqualTo("Strip of 15 · Tablet");
        verify(w.posting).sweepStore(eq(SaleWorld.TENANT), any(), eq(w.shop.id()), any());
    }

    @Test
    void theSearchShowsOnlyProductsTheUnitHasAdoptedOrOwns() {
        UUID a = w.product("Alpha Test", "Paracetamol", "NONE");
        ProductRepository.Row own = row(a, null, null);
        ProductRepository.Row notAdopted = new ProductRepository.Row("GLOBAL", UUID.randomUUID(), null, "G-1", "Global Test", null,
                false, "Salt", "5 mg", "Tablet", "Strip", "Tablet", 10, "30049099", new BigDecimal("12"), "NONE", false,
                "NOT_ADOPTED", false, null);
        when(w.products.search(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new ProductRepository.Slice<>(List.of(notAdopted, own), 2));

        SaleViews.SaleProductList list = service.search(SaleWorld.TENANT, w.cashier.userId(), shop, "test", null);

        assertThat(list.items()).extracting(r -> r.product().name()).containsExactly("Alpha Test");
    }

    @Test
    void theSearchNeedsExactlyOneOfNameAndBarcodeAndAnUnknownBarcodeIsAnEmptyList() {
        assertThatThrownBy(() -> service.search(SaleWorld.TENANT, w.cashier.userId(), shop, null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.search(SaleWorld.TENANT, w.cashier.userId(), shop, "para", "8901234"))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.search(SaleWorld.TENANT, w.cashier.userId(), shop, "a", null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.search(SaleWorld.TENANT, w.cashier.userId(), shop, null, "bad barcode!"))
                .isInstanceOf(InvalidFieldsException.class);
        when(w.products.search(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new ProductRepository.Slice<>(List.of(), 0));

        assertThat(service.search(SaleWorld.TENANT, w.cashier.userId(), shop, null, "8901234567890").items()).isEmpty();
    }

    @Test
    void aBarcodeFindsTheProductThatCarriesIt() {
        UUID a = w.product("Alpha Test", "Paracetamol", "NONE");
        ProductRepository.Full full = w.productRows.get(a);
        w.productRows.put(a, new ProductRepository.Full(full.row(), true, true, "8901234567890", true, null, true, 0L, null));
        when(w.products.find(any(), eq(a))).thenReturn(Optional.of(w.productRows.get(a)));
        found(a);
        when(w.shelfRepository.shelf(any(), any(), any(), any())).thenReturn(Map.of(a,
                new SaleSearchRepository.Shelf(1, new BigDecimal("10"))));

        SaleViews.SaleProductList list = service.search(SaleWorld.TENANT, w.cashier.userId(), shop, null, "8901234567890");

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).matchedOnSalt()).isFalse();
    }

    @Test
    void theSearchHidesTheQuantityOfAnAuditedProductFromABlindCaller() {
        UUID a = w.product("Alpha Test", "Paracetamol", "NONE");
        found(a);
        when(w.shelfRepository.shelf(any(), any(), any(), any())).thenReturn(Map.of(a,
                new SaleSearchRepository.Shelf(8, new BigDecimal("10"))));
        when(w.audits.openAuditProducts(any(), eq(w.shop.id()))).thenReturn(Set.of(a));

        SaleViews.SaleProductRow row = service.search(SaleWorld.TENANT, w.cashier.userId(), shop, "alpha", null).items().get(0);

        assertThat(row.availableQty()).isNull();
        assertThat(row.outOfStock()).isFalse();
        assertThat(row.mrpFrom()).isEqualTo("10.00");
    }
}
