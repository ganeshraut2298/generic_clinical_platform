package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.platform.api.TeamMemberPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the document screens of SAH-79 WP-E share: the History card read from the append-only {@code document_event} table and
 * the people named on a document, by membership id (a name comes from the platform's team port; a member who is gone shows as
 * "Unknown member"). Reads only. Names are returned to the screen and are never logged.
 */
@Component
class DocumentViews {

    private final DocumentEventRepository events;
    private final StoreRepository stores;
    private final TeamMemberPort team;
    private final ApprovalEngine engine;

    DocumentViews(DocumentEventRepository events, StoreRepository stores, TeamMemberPort team, ApprovalEngine engine) {
        this.events = events;
        this.stores = stores;
        this.team = team;
        this.engine = engine;
    }

    /** The events of one document, oldest first, and every person they or {@code extraPeople} name. */
    record Loaded(List<DocumentEventRepository.Event> events, Map<UUID, TeamMemberPort.TeamMember> members) {

        PharmacyViews.Person person(UUID membershipId) {
            if (membershipId == null) {
                return null;
            }
            TeamMemberPort.TeamMember member = members.get(membershipId);
            return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
        }
    }

    Loaded load(UUID tenantId, String docType, UUID docId, Collection<UUID> extraPeople) {
        List<DocumentEventRepository.Event> history = events.forDocument(tenantId, docType, docId);
        Set<UUID> people = new LinkedHashSet<>();
        for (UUID id : extraPeople) {
            if (id != null) {
                people.add(id);
            }
        }
        history.forEach(e -> {
            if (e.actorMembershipId() != null) {
                people.add(e.actorMembershipId());
            }
        });
        return new Loaded(history, people.isEmpty() ? Map.of() : team.findAll(tenantId, people));
    }

    List<StockViews.HistoryEvent> history(UUID tenantId, Loaded loaded) {
        Map<UUID, Store> storeById = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        List<StockViews.HistoryEvent> views = new ArrayList<>();
        for (DocumentEventRepository.Event e : loaded.events()) {
            Store eventStore = storeById.get(e.storeId());
            views.add(new StockViews.HistoryEvent(e.event(), e.occurredAt(), loaded.person(e.actorMembershipId()), e.actingAs(),
                    eventStore == null ? null : OpeningStockPostService.storeRef(eventStore), null, e.qtyBefore(), e.qtyAfter(),
                    engine.reasonRef(tenantId, e.reasonKey(), null)));
        }
        return views;
    }
}
