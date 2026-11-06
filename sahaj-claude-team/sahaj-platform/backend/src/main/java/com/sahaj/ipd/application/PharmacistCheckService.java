package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineOrderRepository.Position;
import com.sahaj.ipd.application.MedicineOrderRepository.WaitingKey;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecksSummary;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.domain.Fingerprint;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-31 the pharmacist check: the queue of orders waiting for the first dose, its counts-only summary, and Verify
 * order with the personal staff PIN (ADR-009 D5, D8, D11; ADR-010 D9).
 *
 * <p>Each read runs in one REPEATABLE READ transaction, so the count, the rows and their checks agree (ADR-005 D6).
 * The list shows names, UHIDs and allergy texts, so it is not read-only: it writes one record-access row per distinct
 * patient of the page in the same transaction (security review S3, which re-opens ADR-009 Q10). The summary is counts
 * only and writes nothing. Both are allowed in Read-only state.
 *
 * <p>{@link #verify} is deliberately <b>not</b> transactional: the PIN check must run outside any transaction and
 * commit before the IPD transaction starts (so a wrong try is counted even though the request fails, and no platform
 * row lock is held together with the admission lock). It calls two transactional methods of
 * {@link MedicineOrderService} (a different bean, so each call goes through the transaction proxy).
 */
@Service
public class PharmacistCheckService {

    private final MedicineOrderService orders;
    private final MedicineOrderRepository repository;
    private final MedicineAssembler assembler;
    private final StaffPinPort staffPin;
    private final TeamMemberPort team;
    private final IpdGate gate;

    PharmacistCheckService(MedicineOrderService orders, MedicineOrderRepository repository,
                           MedicineAssembler assembler, StaffPinPort staffPin, TeamMemberPort team, IpdGate gate) {
        this.orders = orders;
        this.repository = repository;
        this.assembler = assembler;
        this.staffPin = staffPin;
        this.team = team;
        this.gate = gate;
    }

    /**
     * The panel "Pharmacist check", one page. Check order: role (403), module Off (403), limit or cursor (400), the role
     * read again from the membership (403). The caller sends the limit and the cursor as text so that a bad value is
     * the contract's own 400. A refused call writes nothing; a served page writes the record-access rows.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PharmacistChecks list(UUID tenantId, UUID actor, String role, String limitText, String cursorText) {
        MedicineOrderService.requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        ModuleEntitlement ent = gate.check(tenantId, false);
        Integer limit = MedicineRules.limit(limitText);
        if (limit == null) {
            throw new InvalidInputException("limit", MedicineRules.RELOAD_MESSAGE);
        }
        Position after = null;
        if (cursorText != null) {
            after = MedicineRules.decodeCursor(cursorText);
            if (after == null) {
                throw new InvalidInputException("cursor", MedicineRules.RELOAD_MESSAGE);
            }
        }
        pharmacistMembership(tenantId, actor);
        return assembler.queue(tenantId, actor, ent, limit, after);
    }

    /** Counts only: no names, no drugs, no text (ADR-005 D3). The change token is the ADR-007 R1 fingerprint. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PharmacistChecksSummary summary(UUID tenantId, UUID actor, String role) {
        MedicineOrderService.requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        gate.check(tenantId, false);
        pharmacistMembership(tenantId, actor);
        Instant asOf = repository.databaseNow();
        List<WaitingKey> keys = repository.findWaitingKeys(tenantId);
        return new PharmacistChecksSummary(keys.size(), changeToken(keys), asOf);
    }

    /**
     * Verify order. The first transaction checks everything that does not need the PIN and commits; then the PIN is
     * checked through {@link StaffPinPort} with no transaction active ({@code requireOk} throws the platform's
     * {@code StaffPinException}: wrong-pin, pin-locked, pin-not-set, rate-limit or pin-unavailable); then the second
     * transaction locks, decides and writes. A right PIN is valid for this call only.
     */
    public PharmacistActionResult verify(UUID tenantId, UUID actor, String role, UUID orderId,
                                         VerifyOrderInput input) {
        String pin = orders.prepareVerify(tenantId, actor, role, orderId, input);
        staffPin.check(tenantId, actor, pin).requireOk();
        return orders.verifyLocked(tenantId, actor, role, orderId, input);
    }

    /**
     * ADR-007 R1 over the orders counted as waiting: the lines {@code <id>:<version>} (id lower case) sorted, hashed.
     * It holds no name, drug or text, and changes when an order starts or stops waiting.
     */
    static String changeToken(List<WaitingKey> keys) {
        List<String> lines = new ArrayList<>();
        for (WaitingKey key : keys) {
            lines.add(key.id().toString().toLowerCase(Locale.ROOT) + ":" + key.version());
        }
        Collections.sort(lines);
        return Fingerprint.of(lines);
    }

    private void pharmacistMembership(UUID tenantId, UUID actor) {
        MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES,
                MedicineRules.ONLY_PHARMACIST);
    }
}
