package com.sahaj.opd.web;

import com.sahaj.opd.application.DiagnosisService;
import com.sahaj.opd.application.MedicineService;
import com.sahaj.platform.api.ClinicUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-7 medicines, the favourites of the signed-in clinic and the ICD-10 list. The lists are global
 * and read-only (AC5); only the owner marks or unmarks a favourite, and favourites show for that
 * clinic only (AC3).
 */
@RestController
@RequestMapping("/api/v1/opd")
public class MedicineController {

    private final MedicineService medicineService;
    private final DiagnosisService diagnosisService;

    public MedicineController(MedicineService medicineService, DiagnosisService diagnosisService) {
        this.medicineService = medicineService;
        this.diagnosisService = diagnosisService;
    }

    @GetMapping("/medicines")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public MedicinePageResponse medicines(@RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "false") boolean favouritesOnly,
                                           @RequestParam(defaultValue = "0") int page) {
        return MedicinePageResponse.from(
                medicineService.search(ClinicUser.current().tenantId(), q, favouritesOnly, page));
    }

    @PutMapping("/medicine-favourites/{medicineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(OpdRoles.OWNER)
    public void markFavourite(@PathVariable UUID medicineId) {
        ClinicUser user = ClinicUser.current();
        medicineService.markFavourite(user.tenantId(), user.userId(), medicineId);
    }

    @DeleteMapping("/medicine-favourites/{medicineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(OpdRoles.OWNER)
    public void unmarkFavourite(@PathVariable UUID medicineId) {
        ClinicUser user = ClinicUser.current();
        medicineService.unmarkFavourite(user.tenantId(), user.userId(), medicineId);
    }

    @GetMapping("/diagnoses")
    @PreAuthorize(OpdRoles.ANY_CLINIC_ROLE)
    public List<DiagnosisResponse> diagnoses(@RequestParam(required = false) String q) {
        return diagnosisService.search(q).stream().map(DiagnosisResponse::from).toList();
    }
}
