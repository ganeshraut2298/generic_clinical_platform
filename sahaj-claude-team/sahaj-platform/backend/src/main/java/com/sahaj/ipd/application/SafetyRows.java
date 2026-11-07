package com.sahaj.ipd.application;

import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.Response;
import com.sahaj.ipd.application.SafetyViews.Row;
import com.sahaj.ipd.domain.EscalationClock;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.Fingerprint;
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.ResponseAction;
import com.sahaj.platform.api.ActorDirectoryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-33 pure helpers shared by the board, the summary, the banner and the three actions: the row a screen shows, the
 * board order, the counts, and the staff names looked up once per answer. No database work except through the batch
 * names call it is handed.
 */
final class SafetyRows {

    static final String NAME_FALLBACK = "Team member";

    /**
     * The board order (ADR-007 D8): not closed first, then the higher score first, then the earlier response clock
     * start first (a tie-break not in the mockup), then the id (java.util.UUID order).
     */
    static final Comparator<Detail> ORDER = Comparator
            .comparing((Detail d) -> d.status() == Status.DONE)
            .thenComparing(Comparator.comparingInt(Detail::ewsScore).reversed())
            .thenComparing((Detail d) -> EscalationClock.start(d.raisedAt(), d.reopenedAt()))
            .thenComparing(Detail::id);

    private SafetyRows() {
    }

    /**
     * What the caller may do. Both the role in the token and the role read from the membership must be in the set,
     * so a button that is shown is never refused for the role (ADR-007 D9).
     */
    record Access(String tokenRole, String memberRole) {

        boolean doctor() {
            return EscalationRules.DOCTOR_ROLES.contains(tokenRole) && EscalationRules.DOCTOR_ROLES.contains(memberRole);
        }

        boolean nurse() {
            return EscalationRules.NURSE_ROLES.contains(tokenRole) && EscalationRules.NURSE_ROLES.contains(memberRole);
        }
    }

    /** Staff names for one answer, loaded in one batch call; an unknown user is "Team member". */
    static final class ActorNames {
        private final Map<UUID, String> known;

        private ActorNames(Map<UUID, String> known) {
            this.known = known;
        }

        /** One batch lookup of everyone the escalations name: who raised, acknowledged and closed them. */
        static ActorNames forEscalations(ActorDirectoryPort actors, UUID tenantId, Collection<Detail> escalations) {
            Set<UUID> ids = new HashSet<>();
            for (Detail d : escalations) {
                ids.add(d.raisedBy());
                if (d.acknowledgedBy() != null) {
                    ids.add(d.acknowledgedBy());
                }
                if (d.closedBy() != null) {
                    ids.add(d.closedBy());
                }
            }
            return forIds(actors, tenantId, ids);
        }

        static ActorNames forIds(ActorDirectoryPort actors, UUID tenantId, Collection<UUID> ids) {
            Map<UUID, String> found = ids.isEmpty() ? Map.of() : actors.displayNames(tenantId, ids);
            return new ActorNames(found == null ? new HashMap<>() : found);
        }

        String of(UUID userId) {
            String name = userId == null ? null : known.get(userId);
            return name == null ? NAME_FALLBACK : name;
        }
    }

    /** The one definition of "past target" for an escalation as stored: {@link EscalationClock#pastTarget}. */
    static boolean pastTarget(Detail d, Instant asOf) {
        return EscalationClock.pastTarget(d.status(), EscalationClock.start(d.raisedAt(), d.reopenedAt()),
                d.targetMinutes(), asOf);
    }

    /** The row of the panel, with every minute count and the overdue flag taken against {@code asOf}. */
    static Row row(Detail d, String patientName, String bedCode, ActorNames names, Instant asOf, Access access) {
        Instant start = EscalationClock.start(d.raisedAt(), d.reopenedAt());
        boolean open = d.status() == Status.OPEN;
        boolean notClosed = d.status() != Status.DONE;
        Response response = d.status() == Status.DONE ? response(d, names) : null;
        return new Row(d.id(), d.admissionId(), d.version(), patientName, bedCode, d.ewsScore(), d.ewsRisk(),
                d.targetMinutes(), d.status(), d.raisedAt(), names.of(d.raisedBy()),
                EscalationClock.minutesSinceRaised(d.raisedAt(), asOf), d.reopenedAt(),
                EscalationClock.waitingMinutes(start, asOf),
                pastTarget(d, asOf), d.seniorCalls(),
                d.acknowledgedBy() == null ? null : names.of(d.acknowledgedBy()), d.acknowledgedAt(), response,
                open && access.doctor(), notClosed && access.doctor(), open && access.nurse());
    }

    private static Response response(Detail d, ActorNames names) {
        ResponseAction action = ResponseAction.ofStored(d.responseAction()).orElse(null);
        String label = action == null ? d.responseAction() : action.label();
        return new Response(d.responseAction(), label, d.responseNote(), names.of(d.closedBy()), d.closedAt());
    }

    /**
     * The counts of a set of escalations and of the Medicine round: not closed, past target, the dose counts, and the
     * change token of the not closed escalations plus the dose lines (ADR-012 D3). The one function behind the board,
     * the summary and the banner, so the three cannot disagree. Closed escalations in the set are ignored. With no dose
     * in the window ({@link MedicineRound#empty()}) the token is the SAH-33 token.
     */
    static Counts counts(Collection<Detail> escalations, MedicineRound.Result round, Instant asOf) {
        List<Detail> live = new ArrayList<>();
        int past = 0;
        for (Detail d : escalations) {
            if (d.status() != Status.DONE) {
                live.add(d);
                if (pastTarget(d, asOf)) {
                    past++;
                }
            }
        }
        return new Counts(live.size(), past, round.due(), round.late(), changeToken(live, round.tokenLines()), asOf);
    }

    /**
     * ADR-007 R1 and ADR-012 D3: the escalations counted in {@code notClosed} as the lines {@code <id>:<version>} (id
     * in canonical lower case), plus the dose lines of {@link MedicineRound.Result#tokenLines()}, all sorted together;
     * the UTF-8 text of the lines joined by a line feed (empty text for none); SHA-256; the first 16 bytes; base64url
     * without padding (22 characters, the {@link Fingerprint}). It holds no name, score or text. It changes when an
     * escalation is raised, acknowledged, closed, re-opened or gets a higher score (all change the set or the version)
     * or when a dose line changes; a senior call leaves the version alone. With no dose line this is the SAH-33 token.
     */
    static String changeToken(Collection<Detail> notClosed, Collection<String> doseLines) {
        List<String> lines = new ArrayList<>();
        for (Detail d : notClosed) {
            lines.add(d.id().toString().toLowerCase(Locale.ROOT) + ":" + d.version());
        }
        lines.addAll(doseLines);
        Collections.sort(lines);
        return Fingerprint.of(lines);
    }
}
