package com.sahaj.platform.application;

import com.sahaj.platform.domain.NewSalesEnquiry;
import java.util.UUID;

/**
 * Port the application layer saves a sales enquiry through. {@code infrastructure.persistence}
 * provides the only implementation; the application layer never names a concrete adapter.
 */
public interface SalesEnquiryRepository {

    /** Saves the enquiry and returns the id it was given. */
    UUID insert(NewSalesEnquiry enquiry);
}
