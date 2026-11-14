package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrMedicineRepository.MedicineRow;
import com.sahaj.opd.application.emr.EmrViews.AllergyClass;
import com.sahaj.opd.application.emr.EmrViews.MedicineOption;
import com.sahaj.opd.application.emr.EmrViews.MedicinePage;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The EMR reads that feed the prescription: the allergy classes, the medicine search ranked by the doctor's own use and
 * the clinic's favourites with the row defaults (SAH-48-1, SAH-48-2; ADR-022 D4, D5, H6). Doctors only
 * ({@code clinic.consultation.write}). Search text is never logged.
 */
@Service
public class EmrMedicineService {

    static final int PAGE_SIZE = EmrQueryRules.PAGE_SIZE;

    private final EmrAccess access;
    private final EmrMedicineRepository medicines;

    public EmrMedicineService(EmrAccess access, EmrMedicineRepository medicines) {
        this.access = access;
        this.medicines = medicines;
    }

    @Transactional(readOnly = true)
    public List<AllergyClass> allergyClasses(ClinicUser user) {
        access.requireConsultationWrite(user);
        return medicines.allergyClasses().stream().map(c -> new AllergyClass(c.classKey(), c.label())).toList();
    }

    @Transactional(readOnly = true)
    public MedicinePage search(ClinicUser user, String q, Integer page) {
        TeamMember member = access.requireConsultationWrite(user);
        String text = EmrQueryRules.searchText(q);
        int pageNo = EmrQueryRules.page(page);
        EmrMedicineRepository.MedicinePage found = medicines.search(user.tenantId(), member.membershipId(), text,
                PAGE_SIZE, pageNo * PAGE_SIZE);
        return new MedicinePage(found.items().stream().map(EmrMedicineService::option).toList(), found.total(),
                pageNo, PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public List<MedicineOption> favourites(ClinicUser user) {
        access.requireConsultationWrite(user);
        return medicines.favourites(user.tenantId()).stream().map(EmrMedicineService::option).toList();
    }

    /**
     * A medicine with no seeded defaults gets its SAH-7 schedule when that is one of the four frequencies, else 1-0-1;
     * 5 days; after food (ADR-022 H6, accepted in DEC-042).
     */
    static MedicineOption option(MedicineRow row) {
        String frequency = row.defaultFrequency() != null ? row.defaultFrequency()
                : EmrComponentValues.FREQUENCIES.contains(row.schedule()) ? row.schedule() : "1-0-1";
        int days = row.defaultDays() != null ? row.defaultDays() : 5;
        String when = row.defaultWhen() != null ? row.defaultWhen() : "AFTER_FOOD";
        return new MedicineOption(row.id(), row.name(), row.form(), row.favourite(), row.allergyClass(), frequency,
                days, when, row.useCount());
    }
}
