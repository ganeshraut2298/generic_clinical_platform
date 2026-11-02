package com.sahaj.platform.web;

import com.sahaj.platform.application.SalesEnquiryService;
import com.sahaj.platform.domain.NewSalesEnquiry;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public "Contact sales" and "Book a Demo" endpoint (SAH-2). No login. The per-address limit is
 * applied before this method by {@code EnquiryRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/v1/platform/public")
public class SalesEnquiryController {

    private final SalesEnquiryService service;

    public SalesEnquiryController(SalesEnquiryService service) {
        this.service = service;
    }

    @PostMapping("/sales-enquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse create(@Valid @RequestBody SalesEnquiryRequest request) {
        UUID id = service.create(new NewSalesEnquiry(
                request.name(), request.clinicCity(), request.mobile(), request.bestTime(), request.source()));
        return new IdResponse(id);
    }
}
