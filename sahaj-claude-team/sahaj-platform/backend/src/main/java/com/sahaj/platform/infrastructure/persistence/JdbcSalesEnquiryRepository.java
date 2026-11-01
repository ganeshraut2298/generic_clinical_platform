package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.SalesEnquiryRepository;
import com.sahaj.platform.domain.NewSalesEnquiry;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code platform.sales_enquiry}. The table is insert-only for {@code sahaj_app} (it cannot
 * read it back), so the id is generated here and the statement has no {@code RETURNING} clause.
 */
@Repository
public class JdbcSalesEnquiryRepository implements SalesEnquiryRepository {

    private static final String INSERT = """
            insert into platform.sales_enquiry (id, name, clinic_city, mobile, best_time, source)
            values (:id, :name, :clinicCity, :mobile, :bestTime, :source)
            """;

    private final JdbcClient jdbc;

    public JdbcSalesEnquiryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(NewSalesEnquiry enquiry) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT)
                .param("id", id)
                .param("name", enquiry.name())
                .param("clinicCity", enquiry.clinicCity())
                .param("mobile", enquiry.mobile())
                .param("bestTime", enquiry.bestTime() == null ? null : enquiry.bestTime().name())
                .param("source", enquiry.source().name())
                .update();
        return id;
    }
}
