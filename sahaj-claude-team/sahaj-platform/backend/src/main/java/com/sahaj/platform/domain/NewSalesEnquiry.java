package com.sahaj.platform.domain;

/**
 * A sales enquiry as it is saved. {@code mobile} is personal data: it is stored and never logged,
 * and {@link #toString()} leaves it out so an accidental log line cannot carry it.
 */
public record NewSalesEnquiry(String name, String clinicCity, String mobile, BestTime bestTime, EnquirySource source) {

    @Override
    public String toString() {
        return "NewSalesEnquiry[source=" + source + "]";
    }
}
