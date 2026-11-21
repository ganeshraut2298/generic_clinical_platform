package com.sahaj.pharmacy.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The world of the SAH-80 sale tests: the real gate, store guard, people and audit of {@link PharmacyFixture} (17 Nov 2026,
 * 11:05 AM IST), a sale-allowed pharmacy store with a pharmacist, a cashier and the owner, and Mockito doubles for the SAH-80
 * repositories and the patient ports. Products and stock are put in with {@link #product} and {@link #shelf}.
 */
final class SaleWorld {

    static final UUID TENANT = PharmacyFixture.TENANT;
    static final LocalDate TODAY = PharmacyFixture.TODAY;

    final PharmacyFixture f = new PharmacyFixture();
    final PharmacyViews.Store central = f.createCentral();
    final PharmacyViews.Store shop = f.createStore("Test Main Pharmacy", "MP", StoreType.PHARMACY);
    final PharmacyFixture.Person cashier = f.person("BILLING", "Test Cashier One", true);
    final PharmacyFixture.Person pharmacist2 = f.person("PHARMACIST", "Test Pharmacist Two", true);

    final CustomerRepository customers = mock(CustomerRepository.class);
    final HeldBillRepository held = mock(HeldBillRepository.class);
    final SaleBillRepository bills = mock(SaleBillRepository.class);
    final SaleRegisterRepository registers = mock(SaleRegisterRepository.class);
    final SaleSearchRepository shelfRepository = mock(SaleSearchRepository.class);
    final ProductRepository products = mock(ProductRepository.class);
    final StockRepository stock = mock(StockRepository.class);
    final StockPosting posting = mock(StockPosting.class);
    final ApprovalRuleRepository rules = mock(ApprovalRuleRepository.class);
    final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    final DocumentEventRepository events = mock(DocumentEventRepository.class);
    final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    final StoreBillSeriesRepository storeSeries = mock(StoreBillSeriesRepository.class);
    final PrintTemplateRepository templates = mock(PrintTemplateRepository.class);
    final StockAuditRepository audits = mock(StockAuditRepository.class);
    final ActiveReasonService reasons = mock(ActiveReasonService.class);
    final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    final PatientLookupPort patientLookup = mock(PatientLookupPort.class);
    final PatientClinicalPort patientClinical = mock(PatientClinicalPort.class);
    final PatientAccessPort patientAccess = mock(PatientAccessPort.class);
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    final BlindCount blind = new BlindCount(audits);
    final SaleSupport support = new SaleSupport(f.unitRepository, patientLookup, patientClinical, patientAccess, f.team,
            templates, f.letterhead, f.time);
    final DiscountRequestRepository discountRequests = mock(DiscountRequestRepository.class);
    final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
    final DiscountApprovals approvals = new DiscountApprovals(discountRequests, approvalRepository);
    final SalePricer pricer = new SalePricer(products, stock, posting, support, rules, customers, blind, reasons, approvals,
            f.time);
    final SaleBillAssembler assembler = new SaleBillAssembler(bills, products, stock, f.storeRepository, customers, events,
            reasons, support, f.time);

    final Map<UUID, ProductRepository.Full> productRows = new HashMap<>();
    final Map<UUID, List<StockRepository.Bal>> balances = new HashMap<>();

    SaleWorld() {
        f.map(shop, f.pharmacist, StoreRole.STORE_USER);
        f.map(shop, cashier, StoreRole.CASHIER);
        f.map(shop, pharmacist2, StoreRole.STORE_USER);
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(clients.own(any(), any(), any())).thenReturn(Optional.empty());
        when(reasons.isActive(any(), any(ReasonList.class), any())).thenReturn(true);
        when(reasons.labels(any())).thenReturn(Map.of("cancel-bill.wrong-item", "Wrong item",
                "allergy-override.confirmed", "Prescriber confirmed", "h1-override.confirmed", "Prescriber confirmed",
                "substitution.out-of-stock", "Out of stock"));
        when(rules.current(any())).thenReturn(Optional.empty());
        when(products.findAll(any(), anyCollection())).thenAnswer(inv -> {
            Map<UUID, ProductRepository.Full> out = new HashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(1)) {
                if (productRows.containsKey(id)) {
                    out.put((UUID) id, productRows.get(id));
                }
            }
            return out;
        });
        when(products.find(any(), any())).thenAnswer(inv -> Optional.ofNullable(productRows.get((UUID) inv.getArgument(1))));
        when(stock.availableOfProduct(any(), any(), any())).thenAnswer(inv -> balances.getOrDefault(inv.getArgument(2), List.of()));
        when(stock.lockBalances(any(), any(), anyCollection())).thenAnswer(inv -> {
            List<StockRepository.Bal> out = new ArrayList<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(2)) {
                out.addAll(balances.getOrDefault(id, List.of()));
            }
            return out;
        });
        when(numbers.next(any(), any())).thenReturn(1L);
        when(numbers.nextStoreBill(any(), any())).thenReturn(1L);
        when(customers.creditBillsSince(any(), anyCollection(), any())).thenReturn(Map.of());
        when(customers.findAll(any(), anyCollection())).thenReturn(Map.of());
        when(bills.hasReturns(any(), any())).thenReturn(false);
        when(held.countOpen(any(), any())).thenReturn(0);
        when(bills.pharmacistRegistration(any(), any())).thenReturn(Optional.empty());
        when(posting.post(any(), any())).thenReturn(List.of());
        when(templates.find(any(), any())).thenReturn(Optional.empty());
        // a held list and a count call with any int stay quiet
        when(held.open(any(), any(), anyInt())).thenReturn(List.of());
        when(customers.byPhone(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(customers.search(any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new CustomerRepository.Slice(List.of(), 0));
        when(bills.page(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SaleBillRepository.ListSlice(List.of(), 0));
        when(shelfRepository.shelf(any(), any(), anyCollection(), any())).thenReturn(Map.of());
        when(audits.openAuditProducts(any(), any())).thenReturn(java.util.Set.of());
        when(customers.creditBillsSince(any(), anyCollection(), any())).thenReturn(Map.of());
        when(bills.lines(any(), any())).thenReturn(List.of());
        when(bills.payments(any(), any())).thenReturn(List.of());
        when(bills.saleLedger(any(), any())).thenReturn(List.of());
        when(bills.refundModes(any())).thenReturn(List.of());
        when(events.forDocument(any(), any(), any())).thenReturn(List.of());
        when(series.find(any(), any())).thenReturn(Optional.empty());
        when(storeSeries.find(any(), any())).thenReturn(Optional.empty());
        when(customers.update(any(), any(), anyLong(), any(), any(), any(), anyLong(), any())).thenReturn(true);
        when(customers.switchOff(any(), any(), anyLong(), any())).thenReturn(true);
        when(held.close(any(), any(), anyLong(), any(), any())).thenReturn(true);
        when(bills.markCancelled(any(), any(), anyLong(), any(), any(), any(), anyBoolean(), anyLong())).thenReturn(true);
        when(bills.setRefundCreditNote(any(), any(), anyLong(), any(), any())).thenReturn(true);
        when(stock.findBatch(any(), any())).thenAnswer(inv -> balances.values().stream().flatMap(List::stream)
                .filter(b -> b.batchId().equals(inv.getArgument(1))).findFirst().map(b -> new StockRepository.BatchRow(b.batchId(),
                        b.productId(), b.batchNo(), b.expiryDate(), b.mrp(), b.purchaseRate(), null, b.firstReceivedAt())));
        f.audit.clear();
    }

    UUID tenant() {
        return TENANT;
    }

    PharmacyCaller caller(PharmacyFixture.Person p) {
        return f.gate.require(TENANT, p.userId(), com.sahaj.pharmacy.domain.AccessLevel.PHARMACY_ACCESS);
    }

    /** An active product of the unit with the given schedule ("NONE", "H", "H1", "X"). GST 12 %. */
    UUID product(String name, String salt, String schedule) {
        UUID id = UUID.randomUUID();
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "P-" + name.substring(0, 3).toUpperCase(),
                name, "Test Maker", false, salt, "500 mg", "Tablet", "Strip", "Tablet", 15, "30049099", new BigDecimal("12"),
                schedule, false, "ACTIVE", false, null);
        productRows.put(id, new ProductRepository.Full(row, true, true, null, true, null, true, 0L, null));
        return id;
    }

    /** One AVAILABLE batch of the product in the shop. */
    UUID shelf(UUID productId, String batchNo, LocalDate expiry, int qty, String mrp) {
        UUID batchId = UUID.randomUUID();
        balances.computeIfAbsent(productId, k -> new ArrayList<>()).add(new StockRepository.Bal(UUID.randomUUID(), shop.id(),
                productId, batchId, StockState.AVAILABLE, qty, batchNo, expiry, Instant.parse("2026-01-01T00:00:00Z")
                .plusSeconds(balances.getOrDefault(productId, List.of()).size()), new BigDecimal(mrp), new BigDecimal("1")));
        return batchId;
    }

    SaleInputs.BuyerInput walkIn() {
        return new SaleInputs.BuyerInput("WALK_IN", null, null, "Test Walk-in One", null, false, false);
    }

    SaleInputs.CartLineInput line(UUID productId, int qty) {
        return new SaleInputs.CartLineInput(productId, BigDecimal.valueOf(qty), null, null, null, null, null);
    }

    SaleInputs.CartInput cart(SaleInputs.BuyerInput buyer, String percent, SaleInputs.CartLineInput... lines) {
        return new SaleInputs.CartInput(buyer, List.of(lines), percent, null, null, null);
    }

    /** The customer row of a walk-in with the given credit. */
    CustomerRepository.Row customer(UUID id, String name, long limit, long due, long creditNote, String status) {
        return new CustomerRepository.Row(id, f.units.get(TENANT).id(), "WALK_IN", null, name, "9000000101", null, limit, due,
                creditNote, status, Instant.parse("2026-11-01T00:00:00Z"), 3L);
    }

    UUID unitId() {
        return f.units.get(TENANT).id();
    }

    static <T> PharmacyInputs.Body<T> body(T value) {
        return PharmacyInputs.Body.of(value);
    }
}
