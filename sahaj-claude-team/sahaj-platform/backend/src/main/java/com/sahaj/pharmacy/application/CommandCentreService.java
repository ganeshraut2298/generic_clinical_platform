package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.CommandCentreRepository.ExpiryBand;
import com.sahaj.pharmacy.application.RegisterReadRepository.XBalance;
import com.sahaj.pharmacy.application.RegisterReadRepository.XCount;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.pharmacy.integration.IpdOrderSource;
import com.sahaj.pharmacy.integration.OpdPrescriptionSource;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-20, the Pharmacy Command Center (docs/api/pharmacy-sales.yaml pharmacyCommandCentre; DEC-074 OQ-80-4). Every card is worked
 * out when read, on its own: a card that fails answers state ERROR and the others still answer. Today is the IST day. The owner may
 * leave the store out for "All selling stores" (then nobody is blind); everyone else names a store they can reach (404 otherwise).
 * A caller without readRegisters at the store (a cashier) gets today's sales only. The gross margin is not answered until the
 * accountant has confirmed the landed rates (go-live G-13; there is no switch for it yet, so it is absent). Quantities of an audited
 * product are hidden from a blind caller as everywhere (rule 32). The OPD and IPD cards say "not connected" while those ports are the
 * no-op. Writes nothing (no expiry sweep: it is not run on every Refresh, pass-2 m5).
 */
@Service
public class CommandCentreService {

    private static final String OK = "OK";
    private static final String ERROR = "ERROR";
    private static final String EMPTY = "EMPTY";
    private static final List<String> BANDS = List.of("EXPIRED", "D0_30", "D31_60", "D61_90");

    private final PharmacyGate gate;
    private final StoreRepository stores;
    private final CommandCentreRepository repository;
    private final StockRepository stock;
    private final StockPosting posting;
    private final RegisterReadRepository registers;
    private final UnitRepository units;
    private final OpdPrescriptionSource opd;
    private final IpdOrderSource ipd;
    private final SaleSupport support;
    private final BlindCount blind;
    private final PharmacyTime time;
    private final CardIsolation nested;

    public CommandCentreService(PharmacyGate gate, StoreRepository stores, CommandCentreRepository repository,
                                StockRepository stock, StockPosting posting, RegisterReadRepository registers,
                                UnitRepository units, OpdPrescriptionSource opd, IpdOrderSource ipd, SaleSupport support,
                                BlindCount blind, PharmacyTime time, CardIsolation isolation) {
        this.nested = isolation;
        this.gate = gate;
        this.stores = stores;
        this.repository = repository;
        this.stock = stock;
        this.posting = posting;
        this.registers = registers;
        this.units = units;
        this.opd = opd;
        this.ipd = ipd;
        this.support = support;
        this.blind = blind;
        this.time = time;
    }

    @Transactional
    public CommandCentreViews.CommandCentre get(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.optionalUuid("storeId", rawStoreId, "Choose a store");
        List<Store> reachable = reachable(caller);
        if (reachable.isEmpty()) {
            throw new ForbiddenException("forbidden", "Not allowed", PharmacyGate.NOT_ALLOWED);
        }
        List<Store> scope;
        if (storeId == null) {
            if (!caller.isOwner()) {
                throw new InvalidFieldsException(List.of(new FieldMessage("storeId", "Choose a store")));
            }
            scope = reachable;
        } else {
            scope = reachable.stream().filter(s -> s.id().equals(storeId)).findFirst().map(List::of)
                    .orElseThrow(StoreAccessGuard::notFound);
        }
        Store single = storeId == null ? null : scope.get(0);
        List<UUID> ids = scope.stream().map(Store::id).toList();
        boolean full = caller.isOwner() || caller.isPharmacist() || single != null
                && StoreAccessGuard.holds(caller, single.id(), StoreCapability.READ_REGISTERS);
        Instant asOf = time.now();
        Instant dayStart = time.dayStart();
        Instant dayEnd = time.today().plusDays(1).atStartOfDay(PharmacyTime.IST).toInstant();
        LocalDate today = time.today();

        CommandCentreViews.TodaysSales sales = card(() -> sales(tenantId, ids, dayStart, dayEnd),
                new CommandCentreViews.TodaysSales(ERROR, null, null, null));
        if (!full) {
            return new CommandCentreViews.CommandCentre(asOf, single == null ? null : OpeningStockPostService.storeRef(single),
                    sales, null, null, null, null, null, null, null, null);
        }
        // m5: no expiry sweep here. Opening this screen is a read that is repeated on every Refresh; the bands and the low-stock
        // count work from the expiry dates, and the stock screens run the sweep of the store when they are read.
        BlindCount.Mask mask = single == null ? BlindCount.Mask.NONE : blind.at(caller, single.id());
        List<ExpiryBand> bands = nested.runOr(() -> repository.expiryBands(tenantId, ids, today), null);
        final boolean ok = bands != null;
        final List<ExpiryBand> loaded = ok ? bands : List.of();
        CommandCentreViews.NearExpiry90 near = card(() -> nearExpiry(loaded, ok, mask),
                new CommandCentreViews.NearExpiry90(ERROR, null, null, null));
        CommandCentreViews.ExpiryRisk risk = card(() -> expiryRisk(loaded, ok, mask),
                new CommandCentreViews.ExpiryRisk(ERROR, List.of()));
        CommandCentreViews.Reorder reorder = card(() -> reorder(tenantId, ids, today, mask),
                new CommandCentreViews.Reorder(ERROR, null));
        CommandCentreViews.RxWaiting rx = card(() -> rxWaiting(tenantId, ids),
                new CommandCentreViews.RxWaiting(ERROR, false, null, false, null, null));
        CommandCentreViews.UdharCard udhar = card(() -> udhar(tenantId), new CommandCentreViews.UdharCard(ERROR, null, null));
        CommandCentreViews.LivePrescriptions live = support.integrated(tenantId)
                ? new CommandCentreViews.LivePrescriptions(EMPTY, List.of()) : null;
        CommandCentreViews.Compliance compliance = card(() -> compliance(tenantId, ids, today, mask),
                new CommandCentreViews.Compliance(ERROR, null, null, null));
        return new CommandCentreViews.CommandCentre(asOf, single == null ? null : OpeningStockPostService.storeRef(single), sales,
                null, near, reorder, rx, udhar, risk, live, compliance);
    }

    /** The selling stores the caller can see the command centre of. */
    private List<Store> reachable(PharmacyCaller caller) {
        List<Store> out = new ArrayList<>();
        if (caller.isOwner()) {
            for (Store s : stores.list(caller.tenantId(), true)) {
                if (s.active() && s.saleAllowed()) {
                    out.add(s);
                }
            }
            return out;
        }
        for (PharmacyCaller.Mapping m : caller.mappings()) {
            Store s = m.store();
            if (s.active() && s.saleAllowed() && (StoreAccessGuard.holds(caller, s.id(), StoreCapability.SELL)
                    || StoreAccessGuard.holds(caller, s.id(), StoreCapability.READ_REGISTERS))) {
                out.add(s);
            }
        }
        return out;
    }

    private <T> T card(Supplier<T> work, T failed) {
        // DEC-089 item 6: a savepoint per card, so a failed statement does not abort the transaction the other cards share; any
        // failure of one card (not only a database one) leaves it in its ERROR state (pass-2 m5). A refusal still reaches the caller.
        return nested.runOr(work, failed);
    }

    // ---- cards ----

    private CommandCentreViews.TodaysSales sales(UUID tenantId, List<UUID> ids, Instant from, Instant to) {
        CommandCentreRepository.Sales s = repository.sales(tenantId, ids, from, to);
        long net = s.netPaise() - s.refundedPaise();
        if (s.bills() == 0 && s.refundedPaise() == 0) {
            return new CommandCentreViews.TodaysSales(EMPTY, 0L, 0, 0L);
        }
        return new CommandCentreViews.TodaysSales(OK, net, s.bills(), s.bills() == 0 ? 0L : s.netPaise() / s.bills());
    }

    private CommandCentreViews.NearExpiry90 nearExpiry(List<ExpiryBand> bands, boolean ok, BlindCount.Mask mask) {
        if (!ok) {
            throw new IllegalStateException("bands");
        }
        int batches = 0;
        long value = 0;
        boolean hidden = false;
        for (ExpiryBand b : bands) {
            if ("EXPIRED".equals(b.band())) {
                continue;
            }
            batches += b.batches();
            value += b.valuePaise();
            hidden |= mask.hides(b.productId());
        }
        return new CommandCentreViews.NearExpiry90(batches == 0 ? EMPTY : OK, hidden ? null : value, batches, null);
    }

    private CommandCentreViews.ExpiryRisk expiryRisk(List<ExpiryBand> bands, boolean ok, BlindCount.Mask mask) {
        if (!ok) {
            throw new IllegalStateException("bands");
        }
        Map<String, int[]> counts = new HashMap<>();
        Map<String, Long> values = new HashMap<>();
        Set<String> hidden = new HashSet<>();
        for (ExpiryBand b : bands) {
            counts.computeIfAbsent(b.band(), k -> new int[1])[0] += b.batches();
            values.merge(b.band(), b.valuePaise(), Long::sum);
            if (mask.hides(b.productId())) {
                hidden.add(b.band());
            }
        }
        List<CommandCentreViews.Bar> bars = new ArrayList<>();
        for (String band : BANDS) {
            bars.add(new CommandCentreViews.Bar(band, counts.getOrDefault(band, new int[1])[0],
                    hidden.contains(band) ? null : values.getOrDefault(band, 0L)));
        }
        return new CommandCentreViews.ExpiryRisk(bands.isEmpty() ? EMPTY : OK, bars);
    }

    private CommandCentreViews.Reorder reorder(UUID tenantId, List<UUID> ids, LocalDate today, BlindCount.Mask mask) {
        int count = 0;
        for (UUID id : ids) {
            // L23: for a blind caller the audited products are left out (a hidden product would add one bit to the count)
            count += mask.active() ? (int) stock.lowStock(tenantId, id, today, 1, 0, mask.products()).total()
                    : stock.overview(tenantId, id, today).lowStock();
        }
        return new CommandCentreViews.Reorder(OK, count);
    }

    private CommandCentreViews.RxWaiting rxWaiting(UUID tenantId, List<UUID> ids) {
        boolean opdOn = opd.connected();
        boolean ipdOn = ipd.connected();
        return new CommandCentreViews.RxWaiting(OK, opdOn, opdOn ? 0 : null, ipdOn, ipdOn ? 0 : null,
                repository.indentsWaiting(tenantId, ids));
    }

    private CommandCentreViews.UdharCard udhar(UUID tenantId) {
        CommandCentreRepository.Udhar u = repository.udhar(tenantId, support.sixtyDaysAgo());
        return u.anyLimit() ? new CommandCentreViews.UdharCard(OK, u.duePaise(), u.over60Paise()) : null;
    }

    private CommandCentreViews.Compliance compliance(UUID tenantId, List<UUID> ids, LocalDate today, BlindCount.Mask mask) {
        Instant dayStart = time.dayStart();
        Instant dayEnd = today.plusDays(1).atStartOfDay(PharmacyTime.IST).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(PharmacyTime.IST).toInstant();
        int h1 = 0;
        int overrides = 0;
        int review = 0;
        int counted = 0;
        int neverCounted = 0;
        for (UUID id : ids) {
            h1 += registers.h1Entries(tenantId, id, dayStart, dayEnd, false);
            overrides += registers.h1Entries(tenantId, id, dayStart, dayEnd, true);
            List<XBalance> balances = registers.xBalances(tenantId, id, monthStart, dayEnd, null);
            Map<UUID, XCount> counts = registers.latestCounts(tenantId, id, balances.stream().map(XBalance::productId).toList());
            for (XBalance b : balances) {
                if (mask.hides(b.productId())) {
                    continue; // L23: a blind caller learns nothing about an audited product, not even that its book differs
                }
                XCount c = counts.get(b.productId());
                if (c == null) {
                    neverCounted++;
                } else {
                    counted++;
                    review += c.countedQty() != b.closing() ? 1 : 0;
                }
            }
        }
        Unit unit = units.find(tenantId).orElse(null);
        return new CommandCentreViews.Compliance(OK, new CommandCentreViews.H1Card(h1, overrides),
                new CommandCentreViews.ScheduleXCard(review, review == 0 && !(neverCounted > 0 && counted == 0), neverCounted),
                ComplianceService.status(unit == null ? null : unit.drugLicenceValidTill(), today));
    }
}
