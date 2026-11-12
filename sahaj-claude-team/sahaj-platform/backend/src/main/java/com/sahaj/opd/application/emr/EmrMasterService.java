package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.SearchPrefix;
import com.sahaj.opd.application.emr.EmrMasterRepository.MasterPageRow;
import com.sahaj.opd.application.emr.EmrMasterRepository.QuickOptionRow;
import com.sahaj.opd.application.emr.EmrViews.MasterItem;
import com.sahaj.opd.application.emr.EmrViews.MasterPage;
import com.sahaj.opd.application.emr.EmrViews.QuickOption;
import com.sahaj.opd.application.emr.EmrViews.QuickOptionList;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The EMR quick-option masters (SAH-43-1, SAH-43-2, ADR-019 D3, D10): search of the complaints, investigations and
 * advice masters, and the signed-in doctor's own usage-ranked list for any of the five masters. The masters hold no
 * patient data, so every clinic role searches. The ranking is always the caller's own: no doctor id is read from
 * the request. Neither the search text nor any name is logged.
 */
@Service
public class EmrMasterService {

    private final EmrMasterRepository repository;
    private final EmrAccess access;

    public EmrMasterService(EmrMasterRepository repository, EmrAccess access) {
        this.repository = repository;
        this.access = access;
    }

    /**
     * Prefix search of complaints, investigations or advice: the typed text never acts as a wildcard, at most 20 per
     * page, specialty hint first, then the master's sort order, then name.
     */
    @Transactional(readOnly = true)
    public MasterPage search(String masterKey, String typed, String specialtyCode, Integer pageNumber) {
        EmrMaster master = EmrMaster.fromKey(masterKey).filter(EmrMaster::searchable)
                .orElseThrow(() -> EmrProblemException.badRequest(
                        "Search complaints, investigations or advice. Diagnoses and medicines have their own search."));
        String text = EmrQueryRules.searchText(typed);
        String specialty = EmrQueryRules.specialty(specialtyCode);
        int page = EmrQueryRules.page(pageNumber);
        MasterPageRow found = repository.search(master, SearchPrefix.lowerPattern(text), specialty,
                EmrQueryRules.PAGE_SIZE, page * EmrQueryRules.PAGE_SIZE);
        List<MasterItem> items = found.items().stream()
                .map(row -> new MasterItem(row.id(), row.name(), row.specialtyHint())).toList();
        return new MasterPage(items, found.total(), page, EmrQueryRules.PAGE_SIZE);
    }

    /** The caller's own ranked quick options for one master. Doctors, and an owner with doctor details, only. */
    @Transactional(readOnly = true)
    public QuickOptionList quickOptions(ClinicUser user, String masterKey, Integer limitValue, String specialtyCode) {
        TeamMember doctor = access.requireConsultationWrite(user);
        EmrMaster master = EmrMaster.fromKey(masterKey)
                .orElseThrow(() -> EmrProblemException.badRequest("Choose a master from the list."));
        int limit = EmrQueryRules.limit(limitValue);
        String specialty = EmrQueryRules.specialty(specialtyCode);
        List<QuickOptionRow> rows = repository.ranked(user.tenantId(), doctor.membershipId(), master, specialty,
                limit);
        List<QuickOption> items = rows.stream()
                .map(row -> new QuickOption(row.itemKey(), row.name(), row.icd10Code(), row.form(), row.favourite(),
                        row.useCount(), row.lastUsedAt()))
                .toList();
        return new QuickOptionList(master.key(), items);
    }
}
