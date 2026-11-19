package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.OpeningStockRepository.ImportRow;
import com.sahaj.pharmacy.application.OpeningStockRepository.IssueRow;
import com.sahaj.pharmacy.application.OpeningStockRepository.LineRow;
import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.OpeningStockRules;
import com.sahaj.platform.api.TeamMemberPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the opening-stock answers of the contract (OpeningStockImport, OpeningStockLine, RowIssue). People are named from
 * the team (a removed member still has a name); the store name is read from the store. {@code canApprove} is true only for
 * an OWNER who did not start the import while it waits for approval ({@code approveLockReason} says why not). {@code posted}
 * is true once the import was posted to stock (SAH-79-1).
 */
@Component
class OpeningStockAssembler {

    static final String UNKNOWN_MEMBER = "Unknown member";

    private final StoreRepository stores;
    private final TeamMemberPort team;

    OpeningStockAssembler(StoreRepository stores, TeamMemberPort team) {
        this.stores = stores;
        this.team = team;
    }

    PharmacyViews.OpeningStockImport one(PharmacyCaller caller, ImportRow row) {
        return all(caller, List.of(row)).get(0);
    }

    List<PharmacyViews.OpeningStockImport> all(PharmacyCaller caller, List<ImportRow> rows) {
        Map<UUID, String> storeNames = new HashMap<>();
        stores.list(caller.tenantId(), true).forEach(s -> storeNames.put(s.id(), s.name()));
        Set<UUID> people = new HashSet<>();
        for (ImportRow row : rows) {
            people.add(row.uploadedBy());
            if (row.decidedBy() != null) {
                people.add(row.decidedBy());
            }
            if (row.postedBy() != null) {
                people.add(row.postedBy());
            }
        }
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of()
                : team.findAll(caller.tenantId(), people);
        List<PharmacyViews.OpeningStockImport> views = new ArrayList<>();
        for (ImportRow row : rows) {
            views.add(view(caller, row, storeNames.getOrDefault(row.storeId(), ""), members));
        }
        return views;
    }

    private static PharmacyViews.OpeningStockImport view(PharmacyCaller caller, ImportRow row, String storeName,
                                                         Map<UUID, TeamMemberPort.TeamMember> members) {
        boolean waiting = row.status() == ImportStatus.WAITING_APPROVAL;
        boolean mine = row.uploadedBy().equals(caller.membershipId());
        boolean canApprove = waiting && caller.isOwner() && !mine;
        String lock = null;
        if (waiting && !canApprove) {
            lock = caller.isOwner() ? "SELF_UPLOADED" : "NOT_OWNER";
        }
        return new PharmacyViews.OpeningStockImport(row.id(), row.storeId(), storeName, row.status().name(),
                row.rowCount(), row.receivedRows(), row.lineCount(), row.totalQuantity(), row.totalValuePaise(),
                row.errorCount(), row.warningCount(), person(row.uploadedBy(), members), row.createdAt(),
                row.submittedAt(), row.decidedBy() == null ? null : person(row.decidedBy(), members), row.decidedAt(),
                row.rejectReason() == null ? null : row.rejectReason().name(), row.rejectNote(),
                row.replacedByImportId(), canApprove, lock, row.posted(), row.version(), row.postedAt(),
                row.postedBy() == null ? null : person(row.postedBy(), members));
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? UNKNOWN_MEMBER : member.name());
    }

    static PharmacyViews.RowIssue issue(int rowNo, String field, String code, String severity) {
        return new PharmacyViews.RowIssue(rowNo, field, code, severity, OpeningStockRules.message(code));
    }

    static PharmacyViews.RowIssue issue(IssueRow row) {
        return issue(row.rowNo(), row.field(), row.code(), row.severity());
    }

    /** The lines of a page with the current issues of each. */
    static List<PharmacyViews.OpeningStockLine> lines(List<LineRow> rows, List<IssueRow> issues) {
        Map<UUID, List<PharmacyViews.RowIssue>> byLine = new HashMap<>();
        for (IssueRow issue : issues) {
            byLine.computeIfAbsent(issue.lineId(), k -> new ArrayList<>()).add(issue(issue));
        }
        List<PharmacyViews.OpeningStockLine> views = new ArrayList<>();
        for (LineRow row : rows) {
            views.add(new PharmacyViews.OpeningStockLine(row.rowNo(), row.storeCode(), row.productCode(),
                    row.productId(), row.productName(), row.batch(), row.expiryDate(),
                    ProductAssembler.rate(row.mrp()), ProductAssembler.rate(row.purchaseRate()), row.quantity(),
                    row.valuePaise(), byLine.getOrDefault(row.id(), List.of())));
        }
        return views;
    }
}
