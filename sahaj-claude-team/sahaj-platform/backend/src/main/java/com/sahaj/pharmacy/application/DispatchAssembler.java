package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DispatchRepository.Line;
import com.sahaj.pharmacy.application.DispatchRepository.Receipt;
import com.sahaj.pharmacy.application.DispatchRepository.Row;
import com.sahaj.pharmacy.domain.PrintDocType;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.platform.api.TeamMemberPort;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code Dispatch} answer of docs/api/pharmacy.yaml (SAH-79-11, 79-12): the lines with their batch, the receipt of each
 * received line, the latest receipt, the notes and the issue slip print data. Reads only. Names are returned to the screen and
 * never logged.
 */
@Component
class DispatchAssembler {

    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final IndentRepository indents;
    private final DispatchRepository dispatches;
    private final ApprovalEngine engine;
    private final TeamMemberPort team;
    private final PrintTemplateRepository templates;
    private final UnitRepository unitRows;

    DispatchAssembler(StoreRepository stores, ProductRepository products, StockRepository stock, IndentRepository indents,
                      DispatchRepository dispatches, ApprovalEngine engine, TeamMemberPort team,
                      PrintTemplateRepository templates, UnitRepository unitRows) {
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.indents = indents;
        this.dispatches = dispatches;
        this.engine = engine;
        this.team = team;
        this.templates = templates;
        this.unitRows = unitRows;
    }

    DispatchViews.Dispatch view(PharmacyCaller caller, Row row, UUID pathStoreId) {
        UUID tenantId = caller.tenantId();
        Store from = stores.find(tenantId, row.fromStoreId()).orElseThrow();
        Store to = stores.find(tenantId, row.toStoreId()).orElseThrow();
        Store acting = pathStoreId.equals(to.id()) ? to : from;
        List<Line> lines = dispatches.lines(tenantId, row.id());
        Map<UUID, Receipt> receiptOf = new HashMap<>();
        dispatches.receipts(tenantId, row.id()).forEach(r -> receiptOf.put(r.dispatchLineId(), r));
        Set<UUID> people = new LinkedHashSet<>();
        people.add(row.dispatchedBy());
        if (row.receivedBy() != null) {
            people.add(row.receivedBy());
        }
        if (row.receiptSecondSignerMembershipId() != null) {
            people.add(row.receiptSecondSignerMembershipId());
        }
        Map<UUID, TeamMemberPort.TeamMember> members = team.findAll(tenantId, people);
        Map<UUID, StockViews.ProductRef> productOf = new HashMap<>();
        List<DispatchViews.Line> views = new java.util.ArrayList<>();
        for (Line l : lines) {
            StockViews.ProductRef product = productOf.computeIfAbsent(l.productId(), id -> products.find(tenantId, id)
                    .map(f -> IndentService.productRef(f.row())).orElseThrow());
            StockRepository.BatchRow batch = stock.findBatch(tenantId, l.batchId()).orElseThrow();
            Receipt receipt = receiptOf.get(l.id());
            views.add(new DispatchViews.Line(l.id(), product,
                    new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), l.dispatchedQty(),
                    l.replacedByCs(), engine.reasonRef(tenantId, l.changeReasonKey(), l.changeNote()), l.backOrderedQty(),
                    l.scheduleX(), receipt == null ? null : receipt.acceptedQty(), receipt == null ? null : receipt.rejectedQty(),
                    receipt == null ? null : engine.reasonRef(tenantId, receipt.rejectReasonKey(), receipt.note()),
                    receipt != null));
        }
        String sourceNo = row.reference();
        if (row.indentId() != null) {
            sourceNo = indents.findById(tenantId, row.indentId()).map(IndentRepository.Row::docNo).orElse(row.reference());
        }
        DispatchViews.Receipt receipt = row.receivedBy() == null ? null
                : new DispatchViews.Receipt(person(row.receivedBy(), members), row.receivedAt(),
                        row.receiptSecondSignerMembershipId() == null ? null
                                : person(row.receiptSecondSignerMembershipId(), members));
        return new DispatchViews.Dispatch(row.id(), row.reference(), row.sourceType(),
                row.indentId() != null ? row.indentId() : row.transferId(), sourceNo, OpeningStockPostService.storeRef(from),
                OpeningStockPostService.storeRef(to), row.status(), person(row.dispatchedBy(), members), row.dispatchedAt(),
                "Acting as " + caller.name() + " · " + IndentService.capacity(caller, acting) + " assigned to " + acting.name(),
                views, receipt, new DispatchViews.Notes(row.rejectedNote(), row.shortNote()), printHeader(tenantId),
                row.version());
    }

    private PurchaseViews.PrintHeader printHeader(UUID tenantId) {
        PrintTemplateRepository.Row template = templates.find(tenantId, PrintDocType.ISSUE_SLIP).orElse(null);
        com.sahaj.pharmacy.domain.Unit unit = unitRows.find(tenantId).orElse(null);
        return new PurchaseViews.PrintHeader("ISSUE_SLIP", template == null ? List.of() : template.headerLines(),
                template == null ? null : template.footerNote(), template == null ? "A4" : template.paperSize(),
                unit == null ? null : unit.gstin(), unit == null ? null : unit.drugLicenceNo());
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }
}
