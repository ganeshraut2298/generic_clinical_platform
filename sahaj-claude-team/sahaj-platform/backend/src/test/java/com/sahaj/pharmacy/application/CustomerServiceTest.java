package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-80-2 and 80-4: customers, their account and payments (docs/api/pharmacy-sales.yaml). */
class CustomerServiceTest {

    private final SaleWorld w = new SaleWorld();
    private final CustomerService service = new CustomerService(w.f.gate, w.f.guard, w.customers, w.support, w.clients,
            w.f.auditLog);
    private final String shop = w.shop.id().toString();
    private final UUID customerId = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    private SaleInputs.CustomerCreate create(String name, String phone, Long limit, boolean confirm) {
        return new SaleInputs.CustomerCreate(UUID.randomUUID(), null, name, phone, null,
                limit == null ? null : BigDecimal.valueOf(limit), confirm);
    }

    private PharmacyViews.Outcome<SaleViews.Customer> createAs(PharmacyFixture.Person p, SaleInputs.CustomerCreate in) {
        return service.create(SaleWorld.TENANT, p.userId(), shop, SaleWorld.body(in));
    }

    private void stored(CustomerRepository.Row row) {
        when(w.customers.find(any(), eq(row.id()))).thenReturn(Optional.of(row));
        when(w.customers.findForUpdate(any(), eq(row.id()))).thenReturn(Optional.of(row));
    }

    @Test
    void aWalkInCustomerIsSavedWithItsLimitAndTheAuditRowHoldsNoNameOrPhone() {
        UUID[] saved = new UUID[1];
        org.mockito.Mockito.doAnswer(inv -> {
            CustomerRepository.NewCustomer c = inv.getArgument(1);
            saved[0] = c.id();
            stored(w.customer(c.id(), c.name(), c.creditLimitPaise(), 0, 0, "ACTIVE"));
            return null;
        }).when(w.customers).insert(any(), any(), any());

        PharmacyViews.Outcome<SaleViews.Customer> out = createAs(w.f.pharmacist,
                create("Test Customer One", "9000000101", 500_000L, false));

        assertThat(out.created()).isTrue();
        assertThat(out.view().kind()).isEqualTo("WALK_IN");
        assertThat(out.view().credit().creditLimitPaise()).isEqualTo(500_000L);
        assertThat(out.view().phone()).isEqualTo("9000000101");
        assertThat(w.f.auditActions()).contains("PHARMACY_CUSTOMER_CREATED");
        assertThat(w.f.audit.toString()).doesNotContain("Test Customer One").doesNotContain("9000000101");
        verify(w.clients).take(any(), any(), any(), any(), eq(CustomerService.CREATE), any());
        assertThat(saved[0]).isNotNull();
    }

    @Test
    void aCashierSeesTheMaskedPhoneAndCannotSetACreditLimit() {
        assertThatThrownBy(() -> createAs(w.cashier, create("Test Customer One", "9000000101", 100L, false)))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Only the owner or a pharmacist can set a credit limit."));
        verify(w.customers, never()).insert(any(), any(), any());

        stored(w.customer(customerId, "Test Customer One", 0, 0, 0, "ACTIVE"));
        SaleViews.Customer seen = service.get(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString());
        assertThat(seen.phone()).isEqualTo("+91 90XXX XX101").doesNotContain("9000000101");
        assertThat(seen.phoneMasked()).isTrue();
        assertThat(seen.credit().over60DaysPaise()).isNull();
    }

    @Test
    void theOwnerAndAPharmacistSeeTheWholePhoneAndTheOver60Figure() {
        stored(w.customer(customerId, "Test Customer One", 500_000, 300_000, 0, "ACTIVE"));
        when(w.customers.creditBillsSince(any(), any(), any())).thenReturn(Map.of(customerId, 100_000L));

        SaleViews.Customer seen = service.get(SaleWorld.TENANT, w.f.owner.userId(), shop, customerId.toString());

        assertThat(seen.phone()).isEqualTo("9000000101");
        assertThat(seen.phoneMasked()).isFalse();
        assertThat(seen.credit().duePaise()).isEqualTo(300_000L);
        assertThat(seen.credit().over60DaysPaise()).isEqualTo(200_000L);
    }

    @Test
    void aPhoneAnotherCustomerHasAsksForConfirmationAndConfirmationSavesAnyway() {
        CustomerRepository.Row other = w.customer(customerId, "Test Other One", 0, 0, 0, "ACTIVE");
        when(w.customers.byPhone(any(), eq("9000000101"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(other));

        assertThatThrownBy(() -> createAs(w.f.owner, create("Test Customer One", "9000000101", null, false)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("customer-phone-exists");
                    assertThat(e.getMessage()).isEqualTo("A customer with this phone already exists. Use them?");
                    assertThat(e.extensions().get("customerCandidates").toString()).contains("+91 90XXX XX101")
                            .doesNotContain("9000000101").contains("customerId");
                });

        org.mockito.Mockito.doAnswer(inv -> {
            CustomerRepository.NewCustomer c = inv.getArgument(1);
            stored(w.customer(c.id(), c.name(), 0, 0, 0, "ACTIVE"));
            return null;
        }).when(w.customers).insert(any(), any(), any());
        assertThat(createAs(w.f.owner, create("Test Customer One", "9000000101", null, true)).created()).isTrue();
    }

    @Test
    void theFieldErrorsComeTogetherWithTheContractSentences() {
        assertThatThrownBy(() -> createAs(w.f.owner, create(" ", "123", 5L, false))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).extracting(m -> m.field() + ":" + m.message())
                        .containsExactly("name:Enter the name", "phone:Enter a 10-digit phone number"));
        assertThatThrownBy(() -> service.create(SaleWorld.TENANT, w.f.owner.userId(), shop, SaleWorld.body(
                new SaleInputs.CustomerCreate(UUID.randomUUID(), null, "Test Customer One", null, null, new BigDecimal("-1"),
                        false)))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aReplayOfTheSameKeyAnswersTheFirstCustomerWithNothingWritten() {
        stored(w.customer(customerId, "Test Customer One", 0, 0, 0, "ACTIVE"));
        UUID key = UUID.randomUUID();
        when(w.clients.own(any(), any(), eq(key))).thenReturn(Optional.of(new ClientRequestRepository.Taken(
                w.f.owner.id(), CustomerService.CREATE, customerId)));

        PharmacyViews.Outcome<SaleViews.Customer> out = service.create(SaleWorld.TENANT, w.f.owner.userId(), shop,
                SaleWorld.body(new SaleInputs.CustomerCreate(key, null, "Test Customer One", null, null, null, false)));

        assertThat(out.created()).isFalse();
        assertThat(out.view().id()).isEqualTo(customerId);
        verify(w.customers, never()).insert(any(), any(), any());
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void aKeyUsedForAnotherOperationOrHeldByAnotherCallerIsClientKeyReused() {
        UUID key = UUID.randomUUID();
        when(w.clients.own(any(), any(), eq(key))).thenReturn(Optional.of(new ClientRequestRepository.Taken(
                w.f.owner.id(), "pharmacyHoldBill", UUID.randomUUID())));
        assertThatThrownBy(() -> service.create(SaleWorld.TENANT, w.f.owner.userId(), shop, SaleWorld.body(
                new SaleInputs.CustomerCreate(key, null, "Test Customer One", null, null, null, false))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("client-key-reused"));

        UUID other = UUID.randomUUID();
        when(w.clients.heldByAnyone(any(), eq(other))).thenReturn(true);
        assertThatThrownBy(() -> service.create(SaleWorld.TENANT, w.f.owner.userId(), shop, SaleWorld.body(
                new SaleInputs.CustomerCreate(other, null, "Test Customer One", null, null, null, false))))
                .isInstanceOf(PharmacyProblemException.class);
    }

    @Test
    void aPatientAccountNeedsIntegratedModeAnExistingPatientAndIsCreatedOncePerPatient() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        SaleInputs.CustomerCreate in = new SaleInputs.CustomerCreate(UUID.randomUUID(), patientId, null, null, null, null, false);

        CustomerRepository.Row account = new CustomerRepository.Row(customerId, w.unitId(), "PATIENT", patientId, null, null,
                null, 0, 0, 0, "ACTIVE", java.time.Instant.parse("2026-11-01T00:00:00Z"), 0L);
        when(w.customers.findByPatient(any(), eq(patientId))).thenReturn(Optional.of(account));
        when(w.patientLookup.mobilesOf(any(), any())).thenReturn(Map.of());

        PharmacyViews.Outcome<SaleViews.Customer> existing = createAs(w.f.owner, in);
        assertThat(existing.created()).isFalse();
        assertThat(existing.view().kind()).isEqualTo("PATIENT");
        assertThat(existing.view().name()).isEqualTo("Test Patient One");
        verify(w.customers, never()).insert(any(), any(), any());

        w.f.unitRepository.updateSettings(SaleWorld.TENANT, w.unitId(), 0L, null, null, null,
                com.sahaj.pharmacy.domain.IntegrationMode.STANDALONE, w.f.owner.userId());
        assertThatThrownBy(() -> createAs(w.f.owner, in)).isInstanceOf(IntegrationOffException.class);
    }

    @Test
    void aPatientAccountWithANameOfItsOwnIsRefused() {
        assertThatThrownBy(() -> createAs(w.f.owner, new SaleInputs.CustomerCreate(UUID.randomUUID(), UUID.randomUUID(),
                "Test Name", null, null, null, false))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aStoreThatDoesNotAllowSaleIsA409BeforeTheBodyIsChecked() {
        PharmacyViews.Store ward = w.f.createStore("Test Ward Store", "WS", com.sahaj.pharmacy.domain.StoreType.SUB_STORE);
        w.f.map(ward, w.f.pharmacist, com.sahaj.pharmacy.domain.StoreRole.STORE_USER);

        assertThatThrownBy(() -> service.create(SaleWorld.TENANT, w.f.pharmacist.userId(), ward.id().toString(),
                SaleWorld.body(create(" ", "1", null, false)))).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("sale-not-allowed"));
    }

    @Test
    void anUpdateChangesTheFieldsAuditsTheLimitBeforeAndAfterAndAStaleVersionAnswersTheCurrentRecord() {
        stored(w.customer(customerId, "Test Customer One", 100_000, 0, 0, "ACTIVE"));

        SaleViews.Customer saved = service.update(SaleWorld.TENANT, w.f.owner.userId(), shop, customerId.toString(),
                SaleWorld.body(new SaleInputs.CustomerUpdate(BigDecimal.valueOf(3), "Test Customer Two", "9000000102", null,
                        BigDecimal.valueOf(250_000), false)));

        assertThat(saved.id()).isEqualTo(customerId);
        verify(w.customers).update(any(), eq(customerId), eq(3L), eq("Test Customer Two"), eq("9000000102"), any(),
                eq(250_000L), any());
        PharmacyFixture.Audit audit = w.f.audit.get(0);
        assertThat(audit.action()).isEqualTo("PHARMACY_CUSTOMER_CHANGED");
        assertThat(audit.before()).containsEntry("creditLimitPaise", 100_000L);
        assertThat(audit.after()).containsEntry("creditLimitPaise", 250_000L);
        assertThat(audit.toString()).doesNotContain("Test Customer Two");

        assertThatThrownBy(() -> service.update(SaleWorld.TENANT, w.f.owner.userId(), shop, customerId.toString(),
                SaleWorld.body(new SaleInputs.CustomerUpdate(BigDecimal.valueOf(2), "Test Customer Two", null, null, null, false))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("settings-changed");
                    assertThat(e.extensions()).containsKey("current");
                });
    }

    @Test
    void aCashierMayNotChangeTheLimitButMayChangeTheRest() {
        stored(w.customer(customerId, "Test Customer One", 100_000, 0, 0, "ACTIVE"));

        assertThatThrownBy(() -> service.update(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString(),
                SaleWorld.body(new SaleInputs.CustomerUpdate(BigDecimal.valueOf(3), "Test Customer One", null, null,
                        BigDecimal.valueOf(999), false)))).isInstanceOf(ForbiddenException.class);

        service.update(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString(), SaleWorld.body(
                new SaleInputs.CustomerUpdate(BigDecimal.valueOf(3), "Test Customer Two", null, null,
                        BigDecimal.valueOf(100_000), false)));
        verify(w.customers).update(any(), eq(customerId), anyLong(), eq("Test Customer Two"), any(), any(), eq(100_000L), any());
    }

    @Test
    void switchOffNeedsTheOwnerOrAPharmacistAndIsQuietWhenAlreadyOff() {
        stored(w.customer(customerId, "Test Customer One", 0, 0, 0, "ACTIVE"));
        assertThatThrownBy(() -> service.switchOff(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString()))
                .isInstanceOf(StoreAccessDeniedException.class);

        service.switchOff(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, customerId.toString());
        verify(w.customers).switchOff(any(), eq(customerId), eq(3L), any());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_CUSTOMER_SWITCHED_OFF");

        stored(w.customer(customerId, "Test Customer One", 0, 0, 0, "INACTIVE"));
        service.switchOff(SaleWorld.TENANT, w.f.owner.userId(), shop, customerId.toString());
        assertThat(w.f.audit).hasSize(1);
    }

    @Test
    void aPaymentLowersTheDueWritesOneEntryAndAnswersTheSlip() {
        stored(w.customer(customerId, "Test Customer One", 500_000, 300_000, 40_000, "INACTIVE"));
        ArgumentCaptor<CustomerRepository.NewEntry> entry = ArgumentCaptor.forClass(CustomerRepository.NewEntry.class);
        org.mockito.Mockito.doAnswer(inv -> {
            CustomerRepository.NewEntry e = inv.getArgument(1);
            when(w.customers.findEntry(any(), eq(e.id()))).thenReturn(Optional.of(new CustomerRepository.Entry(e.id(), e.kind(),
                    java.time.Instant.parse("2026-11-17T05:35:00Z"), e.storeId(), "Test Main Pharmacy", null, e.mode(),
                    e.amountPaise(), e.dueAfterPaise(), e.creditNoteBalanceAfterPaise(), e.actor())));
            return null;
        }).when(w.customers).insertEntry(any(), any());

        PharmacyViews.Outcome<SaleViews.CustomerPaymentReceipt> out = service.receivePayment(SaleWorld.TENANT,
                w.cashier.userId(), shop, customerId.toString(), SaleWorld.body(new SaleInputs.CustomerPaymentInput(
                        UUID.randomUUID(), BigDecimal.valueOf(120_000), "CASH")));

        assertThat(out.created()).isTrue();
        assertThat(out.view().amountPaise()).isEqualTo(120_000L);
        assertThat(out.view().dueAfterPaise()).isEqualTo(180_000L);
        assertThat(out.view().customerName()).isEqualTo("Test Customer One");
        verify(w.customers).setBalances(any(), eq(customerId), eq(180_000L), eq(40_000L), any());
        verify(w.customers).insertEntry(any(), entry.capture());
        assertThat(entry.getValue().kind()).isEqualTo("PAYMENT");
        assertThat(entry.getValue().mode()).isEqualTo("CASH");
        assertThat(entry.getValue().storeId()).isEqualTo(w.shop.id());
        assertThat(entry.getValue().actor()).isEqualTo(w.cashier.id());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_CUSTOMER_PAYMENT");
    }

    @Test
    void aPaymentAboveTheDueIsRefusedAndNothingChanges() {
        stored(w.customer(customerId, "Test Customer One", 500_000, 300_000, 0, "ACTIVE"));

        assertThatThrownBy(() -> service.receivePayment(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString(),
                SaleWorld.body(new SaleInputs.CustomerPaymentInput(UUID.randomUUID(), BigDecimal.valueOf(300_001), "UPI"))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("The payment is more than the due."));
        verify(w.customers, never()).setBalances(any(), any(), anyLong(), anyLong(), any());
        verify(w.customers, never()).insertEntry(any(), any());

        assertThatThrownBy(() -> service.receivePayment(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString(),
                SaleWorld.body(new SaleInputs.CustomerPaymentInput(UUID.randomUUID(), BigDecimal.ZERO, "BARTER"))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).hasSize(2));
    }

    @Test
    void theAccountShowsSignedEntriesNewestFirstWithTheRunningDue() {
        stored(w.customer(customerId, "Test Customer One", 500_000, 180_000, 0, "ACTIVE"));
        java.time.Instant t = java.time.Instant.parse("2026-11-17T05:35:00Z");
        when(w.customers.entries(any(), eq(customerId), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(new CustomerRepository.EntrySlice(List.of(
                        new CustomerRepository.Entry(UUID.randomUUID(), "PAYMENT", t, w.shop.id(), "Test Main Pharmacy", null,
                                "CASH", 120_000, 180_000, 0, w.cashier.id()),
                        new CustomerRepository.Entry(UUID.randomUUID(), "CREDIT_BILL", t.minusSeconds(60), w.shop.id(),
                                "Test Main Pharmacy", "SB/26-27/000001", null, 300_000, 300_000, 0, w.cashier.id())), 2));

        SaleViews.CustomerAccount account = service.account(SaleWorld.TENANT, w.cashier.userId(), shop, customerId.toString(),
                null, null);

        assertThat(account.items()).extracting(SaleViews.AccountEntry::kind).containsExactly("PAYMENT", "CREDIT_BILL");
        assertThat(account.items()).extracting(SaleViews.AccountEntry::amountPaise).containsExactly(-120_000L, 300_000L);
        assertThat(account.items().get(1).reference()).isEqualTo("SB/26-27/000001");
        assertThat(account.items().get(0).by().name()).isEqualTo("Test Cashier One");
        assertThat(account.total()).isEqualTo(2);
    }

    @Test
    void theListMasksThePhoneForEveryoneAndAHidesSwitchedOffCustomersFromACashier() {
        CustomerRepository.Row row = w.customer(customerId, "Test Customer One", 0, 0, 0, "ACTIVE");
        when(w.customers.search(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new CustomerRepository.Slice(List.of(row), 1));

        SaleViews.CustomerPage page = service.search(SaleWorld.TENANT, w.f.owner.userId(), shop, "Test", null, "true", null, null);

        assertThat(page.items().get(0).phone()).isEqualTo("+91 90XXX XX101");
        verify(w.customers).search(any(), eq("%test%"), eq(""), any(), eq(true), eq(25), eq(0));
        service.search(SaleWorld.TENANT, w.cashier.userId(), shop, "9000000101", null, "true", "2", "10");
        verify(w.customers).search(any(), eq(""), eq("9000000101"), any(), eq(false), eq(10), eq(20));
    }

    @Test
    void anUnknownCustomerAndAnOverLongQueryAreRefused() {
        when(w.customers.find(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.f.owner.userId(), shop, UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.search(SaleWorld.TENANT, w.f.owner.userId(), shop, "x".repeat(81), null, null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
    }
}
