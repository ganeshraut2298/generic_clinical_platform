package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.LedgerIn;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.StockState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one service that changes stock (ADR-029 P5): every ledger line and every balance change of every feature goes through
 * it, inside the caller's transaction. It takes the stock locks in the fixed order (a caller has already taken the stores, the
 * approval request, the document, the counter, the products and the batches): the missing destination balance rows are inserted
 * in sorted order, then every balance row of each involved store and product is locked in one statement, stores in ascending
 * id, rows by product, batch and state. Everything is then worked out in memory (the expiry move, the quantities) and refused
 * before anything is written; the writes come last. All or nothing: any refusal ends the caller's transaction.
 *
 * <p>Expiry (P4): inside any movement, every locked AVAILABLE row whose batch has expired is first moved to QUARANTINE (ledger
 * {@code EXPIRY_QUARANTINE}, actor SYSTEM, reason {@code damage.expired}). Stock is never negative: a decrease above the
 * AVAILABLE quantity is 409 {@code insufficient-stock} (the check on the table is only the backstop). An expired batch is
 * never received into AVAILABLE (409 {@code batch-expired}). Deadlocks and lock timeouts end the request as 503 database-busy
 * (the pharmacy problem handler). Nothing here logs a name, a batch number or a quantity.
 */
@Component
public class StockPosting {

    static final String EXPIRED_REASON = "damage.expired";

    private final StockRepository stock;
    private final PharmacyTime time;

    public StockPosting(StockRepository stock, PharmacyTime time) {
        this.stock = stock;
        this.time = time;
    }

    /** Who a movement is for: the clinic's unit, the person (null is SYSTEM), the Schedule X signer and the approver. */
    public record Actor(UUID tenantId, UUID unitId, UUID membershipId, UUID secondSignerMembershipId,
                        UUID approvedByMembershipId) {
    }

    /** The document behind a line; null for a system move. */
    public record Doc(String type, UUID id, String no, UUID lineId) {
    }

    /**
     * One change of one balance row: {@code delta} above 0 adds to the (store, batch, state) row, below 0 takes from it.
     * A move between stores or states is two lines with one {@code movementId}. {@code secondSignerMembershipId} is the
     * Schedule X signer of this one line (SAH-79-7, SAH-79-8: a document with a Schedule X line and others carries the signer on
     * the Schedule X lines only); when it is null the line carries the signer of the {@link Actor}, if any.
     */
    public record Line(UUID storeId, UUID productId, UUID batchId, StockState state, int delta, String txnType,
                       String reasonKey, UUID movementId, UUID reversesLedgerId, Doc doc, Integer rowNo,
                       UUID secondSignerMembershipId) {

        public Line(UUID storeId, UUID productId, UUID batchId, StockState state, int delta, String txnType,
                    String reasonKey, UUID movementId, UUID reversesLedgerId, Doc doc, Integer rowNo) {
            this(storeId, productId, batchId, state, delta, txnType, reasonKey, movementId, reversesLedgerId, doc, rowNo, null);
        }
    }

    /** What a line left behind: the quantity of its row after the posting. */
    public record Applied(UUID storeId, UUID productId, UUID batchId, StockState state, int balanceAfter) {
    }

    private static final class Row {
        final Bal bal;
        int qty;
        boolean dirty;

        Row(Bal bal) {
            this.bal = bal;
            this.qty = bal.qty();
        }
    }

    private record Key(UUID storeId, UUID batchId, StockState state) {
    }

    /**
     * Ascending id the way PostgreSQL orders a uuid (unsigned, byte by byte), which is the order of the text form; {@code UUID}'s
     * own order is signed and would put {@code ffff...} first. Every posting uses the same order, and it matches the order of
     * the {@code ORDER BY} that locks the rows of one store.
     */
    private static final Comparator<UUID> UUIDS = Comparator.comparing(UUID::toString);

    /**
     * Posts the lines all or nothing. The lines of a movement keep their order in the ledger. Returns what each line left.
     * Must run inside the caller's transaction.
     */
    public List<Applied> post(Actor actor, List<Line> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        LocalDate today = time.today();

        // lock order step 7, part one: the missing destination rows, in sorted order, inserted and not locked
        TreeSet<String> sortedDestinations = new TreeSet<>();
        Map<String, Line> destinations = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.delta() > 0) {
                String sortKey = line.storeId() + "|" + line.productId() + "|" + line.batchId() + "|" + line.state();
                sortedDestinations.add(sortKey);
                destinations.putIfAbsent(sortKey, line);
            }
        }
        for (String sortKey : sortedDestinations) {
            Line line = destinations.get(sortKey);
            stock.ensureBalance(actor.tenantId(), actor.unitId(), line.storeId(), line.productId(), line.batchId(),
                    line.state(), creator(actor));
        }

        // part two: every balance row of each store and product, one statement per store, stores in ascending id
        TreeMap<UUID, TreeSet<UUID>> productsByStore = new TreeMap<>(UUIDS);
        for (Line line : lines) {
            productsByStore.computeIfAbsent(line.storeId(), k -> new TreeSet<>(UUIDS)).add(line.productId());
        }
        Map<Key, Row> rows = new LinkedHashMap<>();
        for (Map.Entry<UUID, TreeSet<UUID>> entry : productsByStore.entrySet()) {
            for (Bal bal : stock.lockBalances(actor.tenantId(), entry.getKey(), entry.getValue())) {
                rows.put(new Key(bal.storeId(), bal.batchId(), bal.state()), new Row(bal));
            }
        }

        List<LedgerIn> ledger = new ArrayList<>();
        expireInside(actor, rows, today, ledger);

        List<Applied> applied = new ArrayList<>();
        List<FailingLine> refused = new ArrayList<>();
        int firstAvailable = -1;
        for (Line line : lines) {
            Row row = rows.get(new Key(line.storeId(), line.batchId(), line.state()));
            if (row == null) {
                // a row that was not there when the store was locked (a decrease of a row that never existed): nothing to take from
                refused.add(new FailingLine(line.doc() == null ? null : line.doc().lineId(), line.rowNo(), line.productId(), 0));
                if (firstAvailable < 0) {
                    firstAvailable = 0;
                }
                continue;
            }
            if (line.delta() > 0 && line.state() == StockState.AVAILABLE && row.bal.expiryDate().isBefore(today)) {
                throw PharmacyProblemException.batchExpired();
            }
            int after = row.qty + line.delta();
            if (after < 0) {
                refused.add(new FailingLine(line.doc() == null ? null : line.doc().lineId(), line.rowNo(), line.productId(),
                        row.qty));
                if (firstAvailable < 0) {
                    firstAvailable = row.qty;
                }
                continue;
            }
            row.qty = after;
            row.dirty = true;
            ledger.add(new LedgerIn(line.movementId(), line.txnType(), line.doc() == null ? null : line.doc().type(),
                    line.doc() == null ? null : line.doc().id(), line.doc() == null ? null : line.doc().no(),
                    line.doc() == null ? null : line.doc().lineId(), line.storeId(), line.state(), line.productId(),
                    line.batchId(), Math.max(line.delta(), 0), Math.max(-line.delta(), 0), after, actor.membershipId(),
                    line.secondSignerMembershipId() != null ? line.secondSignerMembershipId()
                            : actor.secondSignerMembershipId(),
                    actor.approvedByMembershipId(), line.reasonKey(),
                    line.reversesLedgerId()));
            applied.add(new Applied(line.storeId(), line.productId(), line.batchId(), line.state(), after));
        }
        if (!refused.isEmpty()) {
            throw PharmacyProblemException.insufficientStock(Math.max(firstAvailable, 0),
                    refused.size() > 100 ? refused.subList(0, 100) : refused);
        }

        // step 8: the writes. Balances first (they are locked), then the ledger lines.
        for (Row row : rows.values()) {
            if (row.dirty) {
                stock.setBalance(actor.tenantId(), row.bal.id(), row.qty, creator(actor));
            }
        }
        for (LedgerIn line : ledger) {
            stock.insertLedger(actor.tenantId(), actor.unitId(), line);
        }
        return applied;
    }

    /**
     * The read sweep (P4, rule 17): moves every expired AVAILABLE row of the store to QUARANTINE, skipping a row a movement
     * holds (that movement sweeps it itself). Returns the number of rows moved. The caller runs in a read-write transaction.
     * {@code actorMembershipId} only fills the creator of a new balance row; the ledger lines are SYSTEM's.
     */
    public int sweepStore(UUID tenantId, UUID unitId, UUID storeId, UUID actorMembershipId) {
        List<Bal> expired = stock.lockExpiredAvailable(tenantId, storeId, time.today());
        int moved = 0;
        for (Bal bal : expired) {
            UUID movementId = UUID.randomUUID();
            Bal target = stock.ensureBalanceLocked(tenantId, unitId, storeId, bal.productId(), bal.batchId(),
                    StockState.QUARANTINE, actorMembershipId);
            int quarantineAfter = target.qty() + bal.qty();
            stock.setBalance(tenantId, bal.id(), 0, actorMembershipId);
            stock.setBalance(tenantId, target.id(), quarantineAfter, actorMembershipId);
            stock.insertLedger(tenantId, unitId, expiryLine(movementId, storeId, bal, StockState.AVAILABLE, 0, bal.qty(), 0));
            stock.insertLedger(tenantId, unitId, expiryLine(movementId, storeId, bal, StockState.QUARANTINE, bal.qty(), 0,
                    quarantineAfter));
            moved++;
        }
        return moved;
    }

    /** The usable AVAILABLE rows of a product from locked or read rows, in FEFO order (the pick of P4, in memory). */
    public Fefo.Result pick(List<Bal> availableRows, int quantity) {
        List<Fefo.Source> sources = availableRows.stream()
                .filter(b -> b.state() == StockState.AVAILABLE)
                .map(b -> new Fefo.Source(b.batchId(), b.expiryDate(), b.firstReceivedAt(), b.qty())).toList();
        return Fefo.pick(sources, time.today(), quantity);
    }

    // ---- inside a movement ----

    private void expireInside(Actor actor, Map<Key, Row> rows, LocalDate today, List<LedgerIn> ledger) {
        List<Row> expired = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.bal.state() == StockState.AVAILABLE && row.qty > 0 && row.bal.expiryDate().isBefore(today)) {
                expired.add(row);
            }
        }
        for (Row row : expired) {
            UUID movementId = UUID.randomUUID();
            Key target = new Key(row.bal.storeId(), row.bal.batchId(), StockState.QUARANTINE);
            Row quarantine = rows.get(target);
            if (quarantine == null) {
                Bal created = stock.ensureBalanceLocked(actor.tenantId(), actor.unitId(), row.bal.storeId(),
                        row.bal.productId(), row.bal.batchId(), StockState.QUARANTINE, creator(actor));
                quarantine = new Row(created);
                rows.put(target, quarantine);
            }
            int moved = row.qty;
            row.qty = 0;
            row.dirty = true;
            quarantine.qty += moved;
            quarantine.dirty = true;
            ledger.add(expiryLine(movementId, row.bal.storeId(), row.bal, StockState.AVAILABLE, 0, moved, 0));
            ledger.add(expiryLine(movementId, row.bal.storeId(), row.bal, StockState.QUARANTINE, moved, 0, quarantine.qty));
        }
    }

    private static LedgerIn expiryLine(UUID movementId, UUID storeId, Bal bal, StockState state, int in, int out, int after) {
        return new LedgerIn(movementId, "EXPIRY_QUARANTINE", null, null, null, null, storeId, state, bal.productId(),
                bal.batchId(), in, out, after, null, null, null, EXPIRED_REASON, null);
    }

    /** The creator of a new balance row: the person, or for a system move the person whose read or movement caused it. */
    private static UUID creator(Actor actor) {
        return actor.membershipId() != null ? actor.membershipId() : actor.approvedByMembershipId();
    }
}
