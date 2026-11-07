package com.sahaj.platform.web;

import com.sahaj.platform.application.ColleagueTriesHistoryView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code ColleagueTriesHistory} in {@code docs/api/platform.yaml} (SAH-38, ADR-014 D9). Every field of an entry is
 * always present (null where the contract allows it). The names are for the screen only.
 */
public record ColleagueTriesHistoryResponse(UUID membershipId, Instant asOf, int wrongTries7d, boolean truncated,
                                            List<Entry> entries) {

    /** {@code ColleagueTriesEntry}. */
    public record Entry(String kind, Instant at, UUID byMembershipId, String byName, Integer count) {

        /** Redacted: no name. */
        @Override
        public String toString() {
            return "Entry[kind=" + kind + ", at=" + at + ", count=" + count + "]";
        }
    }

    public static ColleagueTriesHistoryResponse from(ColleagueTriesHistoryView view) {
        List<Entry> entries = view.entries().stream()
                .map(entry -> new Entry(entry.kind(), entry.at(), entry.byMembershipId(), entry.byName(),
                        entry.count()))
                .toList();
        return new ColleagueTriesHistoryResponse(view.membershipId(), view.asOf(), view.wrongTries7d(),
                view.truncated(), entries);
    }
}
