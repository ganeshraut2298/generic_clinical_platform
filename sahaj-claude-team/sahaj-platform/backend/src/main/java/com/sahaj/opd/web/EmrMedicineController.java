package com.sahaj.opd.web;

import com.sahaj.opd.application.emr.EmrMedicineService;
import com.sahaj.opd.application.emr.EmrViews.AllergyClass;
import com.sahaj.opd.application.emr.EmrViews.MedicineOption;
import com.sahaj.opd.application.emr.EmrViews.MedicinePage;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-48 (ADR-022 D4, D5): allergy classes, the doctor's medicine search and the clinic's favourites with the row
 * defaults. Read only; the service refuses every role but a doctor, a visiting doctor and an owner with doctor details.
 */
@RestController
@RequestMapping("/api/v1/opd/emr")
public class EmrMedicineController implements EmrApi {

    private static final String DOCTOR_OR_OWNER = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR')";

    private final EmrMedicineService service;

    public EmrMedicineController(EmrMedicineService service) {
        this.service = service;
    }

    @GetMapping("/allergy-classes")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public List<AllergyClass> allergyClasses() {
        return service.allergyClasses(ClinicUser.current());
    }

    @GetMapping("/medicines")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public MedicinePage medicines(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) Integer page) {
        return service.search(ClinicUser.current(), q, page);
    }

    @GetMapping("/medicine-favourites")
    @PreAuthorize(DOCTOR_OR_OWNER)
    public List<MedicineOption> favourites() {
        return service.favourites(ClinicUser.current());
    }
}
