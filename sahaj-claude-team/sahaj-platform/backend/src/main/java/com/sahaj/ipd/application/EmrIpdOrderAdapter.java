package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineOrderService.CheckOnly;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.OrderFinding;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Finding;
import com.sahaj.ipd.domain.OverrideReason;
import com.sahaj.opd.api.EmrIpdOrderPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-67-1 (ADR-025 D8): implements {@link EmrIpdOrderPort}. The EMR round's new medicines are checked and placed by ipd's own
 * SAH-31 order service, so the catalogue, the allergy, duplicate and interaction checks, the audit, the clinical events and the
 * pharmacist check are exactly those of "Check and order". This class holds no medicine rule: it maps the EMR rows to the
 * service's input and the answers back. The role is the signed-in user's, passed by the caller, so the order roles of SAH-31 apply (a visiting doctor
 * is refused). Allergy text never leaves ipd.
 */
@Component
class EmrIpdOrderAdapter implements EmrIpdOrderPort {

    private final MedicineOrderService orders;
    private final MedicineCatalogue catalogue;

    EmrIpdOrderAdapter(MedicineOrderService orders, MedicineCatalogue catalogue) {
        this.orders = orders;
        this.catalogue = catalogue;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderOptions options(UUID tenantId) {
        List<DrugOption> drugs = new ArrayList<>();
        for (Drug d : catalogue.catalogue().drugs()) {
            drugs.add(new DrugOption(d.key(), d.name(), d.route(), d.highAlert()));
        }
        List<KeyLabel> frequencies = new ArrayList<>();
        for (Frequency f : Frequency.values()) {
            frequencies.add(new KeyLabel(f.key(), f.label()));
        }
        List<KeyLabel> reasons = new ArrayList<>();
        for (OverrideReason r : OverrideReason.values()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return new OrderOptions(List.copyOf(drugs), List.copyOf(frequencies), List.copyOf(reasons));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LineCheck> check(UUID tenantId, UUID actorUserId, String actorRole, UUID admissionId,
                                 List<OrderLine> lines) {
        return checks(lines, line -> orders.checkOnly(tenantId, actorUserId, actorRole, admissionId, input(line)));
    }

    /**
     * DEC-048 H37: never inside a transaction. Each line is checked by the order service in its own short transaction that
     * commits when the line is done, so a hard stop's deny event and audit row are kept when the caller then refuses; a caller
     * that holds a transaction (and so a pooled connection) is refused here instead of waiting for a second connection.
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public List<LineCheck> checkRecorded(UUID tenantId, UUID actorUserId, String actorRole, UUID admissionId,
                                         List<OrderLine> lines) {
        return checks(lines, line -> orders.checkOnly(tenantId, actorUserId, actorRole, admissionId, input(line)));
    }

    private static List<LineCheck> checks(List<OrderLine> lines, java.util.function.Function<OrderLine, CheckOnly> checker) {
        List<LineCheck> out = new ArrayList<>();
        for (OrderLine line : lines) {
            CheckOnly result = checker.apply(line);
            List<String> texts = new ArrayList<>();
            String kind = null;
            if (result.check() != null) {
                for (Finding f : result.check().hardStops()) {
                    texts.add(f.text());
                    kind = kind == null ? f.code().name() : kind;
                }
                for (Finding f : result.check().warnings()) {
                    texts.add(f.warningLine());
                    kind = kind == null ? f.code().name() : kind;
                }
            }
            String token = "NEEDS_REASON".equals(result.outcome()) ? result.check().checkToken() : null;
            out.add(new LineCheck(line.clientKey(), result.drug().name(), result.frequency().label(), result.outcome(),
                    List.copyOf(texts), token, kind));
        }
        return List.copyOf(out);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PlaceResult place(UUID tenantId, UUID actorUserId, String actorRole, UUID admissionId, List<OrderLine> lines) {
        List<Placed> placed = new ArrayList<>();
        for (OrderLine line : lines) {
            MedicineOrderAnswer answer = orders.order(tenantId, actorUserId, actorRole, admissionId, input(line));
            if (!"PLACED".equals(answer.outcome()) || answer.order() == null) {
                List<String> texts = new ArrayList<>();
                String kind = null;
                if (answer.check() != null) {
                    for (OrderFinding f : answer.check().hardStops()) {
                        texts.add(f.text());
                        kind = kind == null ? f.code() : kind;
                    }
                    for (OrderFinding f : answer.check().warnings()) {
                        texts.add(f.text());
                        kind = kind == null ? f.code() : kind;
                    }
                }
                return new PlaceResult(false, List.copyOf(placed), List.of(new LineCheck(line.clientKey(), drugName(line.drugKey()),
                        line.frequencyKey(), answer.outcome(), List.copyOf(texts),
                        answer.check() == null ? null : answer.check().checkToken(), kind)));
            }
            placed.add(new Placed(line.clientKey(), answer.order().id(), answer.order().orderedAt()));
        }
        return new PlaceResult(true, List.copyOf(placed), List.of());
    }

    /** The catalogue name of a drug (the 409 row of the sign names the drug, not its key); the key when it is not found. */
    private String drugName(String drugKey) {
        return catalogue.catalogue().find(drugKey).map(Drug::name).orElse(drugKey);
    }

    private static MedicineOrderInput input(OrderLine line) {
        return new MedicineOrderInput(line.clientKey().toString(), line.drugKey(), line.frequencyKey(),
                line.overrideReasonKey(), line.checkToken());
    }
}
