package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrViews.DoctorContext;
import com.sahaj.opd.application.emr.EmrViews.TemplateChoice;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in doctor's own details for the EMR and the template the EMR would choose (SAH-43-3, SAH-44-4,
 * ADR-019 D7, D9). Read through {@code platform.api}; no copy is made. Only the caller's own details: there is no way
 * to ask for another person's. The name and the registration number are never logged.
 */
@Service
public class EmrDoctorContextService {

    private final EmrAccess access;
    private final TeamMemberPort teamMembers;
    private final EmrTemplateResolver resolver;

    public EmrDoctorContextService(EmrAccess access, TeamMemberPort teamMembers, EmrTemplateResolver resolver) {
        this.access = access;
        this.teamMembers = teamMembers;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public DoctorContext me(ClinicUser user) {
        TeamMember member = access.requireConsultationWrite(user);
        Optional<DoctorDetails> details = teamMembers.doctorDetails(user.tenantId(), member.membershipId());
        String specialty = details.map(DoctorDetails::specialty).orElse(null);
        String degree = details.map(DoctorDetails::degree).orElse(null);
        String registrationNo = details.map(DoctorDetails::registrationNo).orElse(null);
        List<String> missing = new ArrayList<>();
        if (degree == null) {
            missing.add("DEGREE");
        }
        if (registrationNo == null) {
            missing.add("REGISTRATION_NO");
        }
        TemplateChoice choice = resolver.chooseForSpecialty(user.tenantId(), specialty);
        return new DoctorContext(member.membershipId(), member.name(), member.role(), specialty, degree,
                registrationNo, missing, choice);
    }
}
