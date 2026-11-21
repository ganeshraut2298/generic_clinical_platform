package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.integration.PatientSearchPort;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-2, TP-1 (docs/api/pharmacy-sales.yaml pharmacySearchSalePatients and pharmacyGetSalePatient, ADR-030 S6). Integrated mode
 * only. The search goes through the pharmacy-owned {@link PatientSearchPort}, which answers 503 {@code patients-unreachable} until
 * the patient module offers a search (the counter then continues as a walk-in); the card is read through the existing
 * {@code patient.api} ports and writes one record-access entry. Names, mobile numbers and allergy text are for the screen only
 * and are never logged or audited. The search text is never logged.
 */
@Service
public class SalePatientService {

    private static final int MAX_ROWS = 20;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final SaleSupport support;
    private final PatientSearchPort search;
    private final CustomerRepository customers;

    public SalePatientService(PharmacyGate gate, StoreAccessGuard guard, SaleSupport support, PatientSearchPort search,
                              CustomerRepository customers) {
        this.gate = gate;
        this.guard = guard;
        this.support = support;
        this.search = search;
        this.customers = customers;
    }

    /** Up to 20 rows by name: name, age, sex, UHID and the masked mobile; no allergy. Writes nothing. */
    @Transactional(readOnly = true)
    public SaleViews.SalePatientList search(UUID tenantId, UUID userId, String rawStoreId, String rawQ) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        String q = rawQ == null ? "" : rawQ.trim();
        if (q.length() < 2 || q.length() > 80) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type 2 to 80 characters.")));
        }
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        support.requireIntegrated(tenantId);
        List<PatientSearchPort.Hit> hits = search.search(tenantId, q, MAX_ROWS);
        Map<UUID, SaleSupport.PatientCard> cards = support.patientCards(tenantId,
                hits.stream().map(PatientSearchPort.Hit::patientId).collect(Collectors.toList()), true);
        List<SaleViews.SalePatientRow> rows = new ArrayList<>();
        for (PatientSearchPort.Hit hit : hits) {
            SaleSupport.PatientCard card = cards.get(hit.patientId());
            if (card != null) {
                rows.add(row(card));
            }
        }
        rows.sort(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT)));
        return new SaleViews.SalePatientList(rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows);
    }

    /** The patient card with the allergy line and the credit line; one record-access entry in this transaction. */
    @Transactional
    public SaleViews.SalePatient get(UUID tenantId, UUID userId, String rawStoreId, String rawPatientId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID patientId = PharmacyParams.pathId(rawPatientId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        support.requireIntegrated(tenantId);
        SaleSupport.PatientCard card = support.patient(tenantId, patientId, true);
        support.recordOpened(tenantId, card.id(), userId);
        SaleViews.AllergyInfo allergy = SaleSupport.allergyInfo(support.clinical(tenantId, card.id()));
        CustomerRepository.Row account = customers.findByPatient(tenantId, card.id()).orElse(null);
        SaleViews.CreditLine credit = null;
        if (account != null) {
            long recent = customers.creditBillsSince(tenantId, List.of(account.id()), support.sixtyDaysAgo())
                    .getOrDefault(account.id(), 0L);
            credit = support.creditLine(caller, account, recent);
        }
        SaleViews.SalePatientRow row = row(card);
        return new SaleViews.SalePatient(row.patientId(), row.name(), row.age(), row.sex(), row.uhid(), row.maskedMobile(),
                null, allergy, credit);
    }

    private static SaleViews.SalePatientRow row(SaleSupport.PatientCard card) {
        return new SaleViews.SalePatientRow(card.id(), card.name(), card.age(), card.sex(), card.uhid(),
                card.mobile() == null ? null : SaleRules.maskMobile(card.mobile()));
    }
}
