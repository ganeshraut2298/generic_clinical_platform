package com.sahaj.platform.application;

import com.sahaj.platform.domain.NewSalesEnquiry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saves a public sales enquiry. It belongs to no clinic, so no tenant is bound. */
@Service
public class SalesEnquiryService {

    private static final Logger log = LoggerFactory.getLogger(SalesEnquiryService.class);

    private final SalesEnquiryRepository repository;

    public SalesEnquiryService(SalesEnquiryRepository repository) {
        this.repository = repository;
    }

    /** Trims the text fields, saves the enquiry and returns its id. The mobile number is not logged. */
    @Transactional
    public UUID create(NewSalesEnquiry enquiry) {
        NewSalesEnquiry clean = new NewSalesEnquiry(
                enquiry.name().strip(),
                blankToNull(enquiry.clinicCity()),
                enquiry.mobile(),
                enquiry.bestTime(),
                enquiry.source());
        UUID id = repository.insert(clean);
        log.info("Sales enquiry saved id={} source={}", id, clean.source());
        return id;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
