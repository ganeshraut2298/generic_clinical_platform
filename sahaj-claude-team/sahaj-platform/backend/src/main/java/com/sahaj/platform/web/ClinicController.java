package com.sahaj.platform.web;

import com.sahaj.platform.application.ClinicProfileService;
import com.sahaj.platform.application.HolidayService;
import com.sahaj.platform.application.SpecialtyService;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-7 Clinic profile, holidays and specialty template. Any signed-in clinic user may read (the
 * page is view-only for them); every change is owner-only (AC4), checked here and again in the
 * services. The clinic always comes from the signed-in user, never from the request.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class ClinicController {

    private static final String ANY_CLINIC_ROLE = TeamController.ANY_CLINIC_ROLE;
    private static final String OWNER = "hasRole('OWNER')";

    private final ClinicProfileService profileService;
    private final HolidayService holidayService;
    private final SpecialtyService specialtyService;

    public ClinicController(ClinicProfileService profileService, HolidayService holidayService,
                             SpecialtyService specialtyService) {
        this.profileService = profileService;
        this.holidayService = holidayService;
        this.specialtyService = specialtyService;
    }

    @GetMapping("/clinic/profile")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public ClinicProfileDto getProfile(@AuthenticationPrincipal TenantPrincipal principal) {
        return ClinicProfileDto.from(profileService.get(principal.tenantId()));
    }

    @PutMapping("/clinic/profile")
    @PreAuthorize(OWNER)
    public ClinicProfileDto saveProfile(@RequestBody ClinicProfileDto request,
                                         @AuthenticationPrincipal TenantPrincipal principal) {
        return ClinicProfileDto.from(profileService.save(principal.tenantId(), principal.userId(), request.toInput()));
    }

    @GetMapping("/clinic/holidays")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public List<HolidayDto> listHolidays(@AuthenticationPrincipal TenantPrincipal principal) {
        return holidayService.list(principal.tenantId()).stream().map(HolidayDto::from).toList();
    }

    @PostMapping("/clinic/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OWNER)
    public HolidayDto addHoliday(@RequestBody HolidayDto request, @AuthenticationPrincipal TenantPrincipal principal) {
        return HolidayDto.from(holidayService.add(principal.tenantId(), principal.userId(), request.date(),
                request.name(), request.type()));
    }

    @DeleteMapping("/clinic/holidays/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(OWNER)
    public void removeHoliday(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal) {
        holidayService.remove(principal.tenantId(), principal.userId(), id);
    }

    @GetMapping("/specialties")
    @PreAuthorize(ANY_CLINIC_ROLE)
    public List<SpecialtyResponse> listSpecialties() {
        return specialtyService.list().stream().map(SpecialtyResponse::from).toList();
    }

    @PutMapping("/clinic/specialty")
    @PreAuthorize(OWNER)
    public ClinicProfileDto applySpecialty(@RequestBody SpecialtyApplyRequest request,
                                            @AuthenticationPrincipal TenantPrincipal principal) {
        return ClinicProfileDto.from(specialtyService.apply(principal.tenantId(), principal.userId(), request.code()));
    }
}
