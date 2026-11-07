package com.sahaj.platform.application;

import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.application.StaffPinColleagueEventRepository.History;
import com.sahaj.platform.application.StaffPinColleagueEventRepository.Line;
import com.sahaj.platform.application.StaffPinColleagueEventRepository.Unseen;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.domain.StaffPinRow;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-38 (ADR-014 D8 and D9): the notice to the owner of a PIN about wrong tries by colleagues, its "Got it", and the
 * clinic owner's history of one member's colleague tries. Staff data, not patient data: no access row is written.
 * The clinic and the person come from the session only. Nothing here logs or returns a PIN or a hash; only the owner's
 * history carries names, for the screen only (looked up now, never stored or logged).
 */
@Service
public class ColleagueTriesService {

    static final String MSG_SEEN_THROUGH = "Reload the page and try again.";
    static final int MAX_SEEN_THROUGH_LENGTH = 40;
    /** No colleague try is older than the product; also keeps the value inside the database column's range. */
    static final Instant EARLIEST_SEEN_THROUGH = Instant.parse("2020-01-01T00:00:00Z");
    /** Clocks differ a little; a day ahead is tolerated, the database still caps the stored value at its own clock. */
    static final Duration MAX_AHEAD = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(ColleagueTriesService.class);

    private final TeamRepository teamRepository;
    private final StaffPinRepository staffPinRepository;
    private final StaffPinColleagueEventRepository events;
    private final Clock clock;

    public ColleagueTriesService(TeamRepository teamRepository, StaffPinRepository staffPinRepository,
                                 StaffPinColleagueEventRepository events, Clock clock) {
        this.teamRepository = teamRepository;
        this.staffPinRepository = staffPinRepository;
        this.events = events;
        this.clock = clock;
    }

    /** GET /me/pin/colleague-tries. Writes nothing. Counts and state only. */
    @Transactional(readOnly = true)
    public ColleagueTriesNoticeView notice(UUID tenantId, UUID userId) {
        TeamRow me = teamRepository.findActiveByUser(tenantId, userId).orElseThrow(ColleagueTriesService::notAllowed);
        Instant marker = events.latestSeenThrough(tenantId, me.id()).orElse(Instant.EPOCH);
        Unseen unseen = events.unseen(tenantId, me.id(), marker);
        StaffPinRow row = staffPinRepository.find(tenantId, me.id(), false).orElse(null);
        PinState state = row == null ? PinState.NOT_SET : row.state();
        Instant lockedUntil = state == PinState.LOCKED ? row.lockedUntil() : null;
        String reason = state == PinState.NOT_SET && row != null ? row.notSetReason() : null;
        return new ColleagueTriesNoticeView(unseen.count(), unseen.count() > 0 ? unseen.latestAt() : null, state,
                lockedUntil, reason);
    }

    /**
     * POST /me/pin/colleague-tries/seen: "Got it". Records that the person has seen every colleague wrong try up to
     * {@code seenThrough} (the database caps it at its own clock), and only when at least one unseen try lies at or
     * before it; otherwise nothing is written, so a double tap is safe. A try after {@code seenThrough} stays unseen.
     */
    @Transactional
    public void markSeen(UUID tenantId, UUID userId, String seenThrough) {
        Instant through = parse(seenThrough);
        TeamRow me = teamRepository.findActiveByUser(tenantId, userId).orElseThrow(ColleagueTriesService::notAllowed);
        Instant marker = events.latestSeenThrough(tenantId, me.id()).orElse(Instant.EPOCH);
        if (!through.isAfter(marker) || !events.existsWrongBetween(tenantId, me.id(), marker, through)) {
            return;
        }
        events.insertNoticeSeen(me.id(), through, userId);
        log.info("staff-pin-colleague-notice-seen tenantId={} membershipId={}", tenantId, me.id());
    }

    /** GET /team/{id}/pin/colleague-tries: OWNER only. Writes nothing. */
    @Transactional(readOnly = true)
    public ColleagueTriesHistoryView history(UUID tenantId, UUID actorUserId, UUID membershipId) {
        boolean owner = teamRepository.findByUser(tenantId, actorUserId)
                .map(row -> row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE)
                .orElse(false);
        if (!owner) {
            throw new ForbiddenException("forbidden", "Not allowed", "Only the clinic owner can do this.");
        }
        teamRepository.findById(tenantId, membershipId)
                .orElseThrow(() -> new NotFoundException("Team member not found"));
        History history = events.history(tenantId, membershipId, ColleagueTriesHistoryView.MAX_ENTRIES + 1);
        boolean truncated = history.lines().size() > ColleagueTriesHistoryView.MAX_ENTRIES;
        List<Line> shown = truncated ? history.lines().subList(0, ColleagueTriesHistoryView.MAX_ENTRIES)
                : history.lines();
        Map<UUID, String> names = new HashMap<>();
        if (shown.stream().anyMatch(line -> line.byMembershipId() != null)) {
            teamRepository.findAll(tenantId).forEach(member -> names.put(member.id(), member.name()));
        }
        List<ColleagueTriesHistoryView.Entry> entries = new ArrayList<>();
        for (Line line : shown) {
            entries.add(entry(line, names));
        }
        return new ColleagueTriesHistoryView(membershipId, history.asOf(), history.wrongTries(), truncated, entries);
    }

    private static ColleagueTriesHistoryView.Entry entry(Line line, Map<UUID, String> names) {
        if ("LOCKED".equals(line.kind())) {
            return new ColleagueTriesHistoryView.Entry("LOCKED", line.at(), null, null, null);
        }
        String name = names.get(line.byMembershipId());
        String shownName = name == null || name.isBlank() ? ColleagueTriesHistoryView.FORMER_STAFF_MEMBER : name;
        if ("RIGHT_AFTER_WRONG".equals(line.kind())) {
            return new ColleagueTriesHistoryView.Entry("RIGHT_AFTER_WRONG", line.at(), line.byMembershipId(),
                    shownName, null);
        }
        return new ColleagueTriesHistoryView.Entry("WRONG_TRIES", line.at(), line.byMembershipId(), shownName,
                line.count());
    }

    /**
     * The checked "seen through" time, before any database call (security review S38-3, docs/api/platform.yaml): not
     * missing, at most 40 characters, a readable date-time, not more than one day ahead of the server clock and not
     * before 1 Jan 2020 00:00 UTC. Every failure is the same 400, so a value outside the range of the database column
     * never reaches it.
     */
    private Instant parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_SEEN_THROUGH_LENGTH) {
            throw invalid();
        }
        Instant through;
        try {
            through = OffsetDateTime.parse(text.trim()).toInstant();
        } catch (DateTimeException | ArithmeticException ex) {
            throw invalid();
        }
        if (through.isBefore(EARLIEST_SEEN_THROUGH) || through.isAfter(clock.instant().plus(MAX_AHEAD))) {
            throw invalid();
        }
        return through;
    }

    private static InvalidFieldsException invalid() {
        return new InvalidFieldsException(List.of(new FieldMessage("seenThrough", MSG_SEEN_THROUGH)));
    }

    private static ForbiddenException notAllowed() {
        return new ForbiddenException("forbidden", "Not allowed", "You do not have access to this.");
    }
}
