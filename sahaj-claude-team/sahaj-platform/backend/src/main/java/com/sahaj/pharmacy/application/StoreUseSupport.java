package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small helpers the WP-G services share: document numbers, the unit's Central Store, and the history and people of a document. */
final class StoreUseSupport {

    private StoreUseSupport() {
    }

    /** The number as the unit's saved series (or the default) renders it for {@code seq}. */
    static String render(DocumentSeriesRepository series, UUID tenantId, DocType type, long seq, LocalDate today) {
        DocumentSeriesRepository.Row saved = series.find(tenantId, type).orElse(null);
        if (saved == null) {
            return DocumentNumbers.render(type.defaultPrefix(), type.defaultFinancialYearPart(), type.defaultSequenceLength(), seq,
                    today);
        }
        return DocumentNumbers.render(saved.prefix(), saved.financialYearPart(), saved.sequenceLength(), seq, today);
    }

    /** The active Central Store of the unit of {@code from}, or 404 when the unit has none. */
    static Store centralStoreOf(StoreRepository stores, UUID tenantId, Store from) {
        List<Store> all = stores.list(tenantId, true);
        return all.stream().filter(s -> s.active() && s.type() == StoreType.CENTRAL && s.unitId().equals(from.unitId()))
                .findFirst().orElseThrow(() -> new NotFoundException("This record was not found."));
    }

    static PharmacyViews.Person person(UUID membershipId, Map<UUID, com.sahaj.platform.api.TeamMemberPort.TeamMember> members) {
        com.sahaj.platform.api.TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    static String blank(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
