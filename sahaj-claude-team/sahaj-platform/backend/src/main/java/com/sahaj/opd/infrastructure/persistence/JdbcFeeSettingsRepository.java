package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.FeeSettingsRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The fee columns of {@code opd.opd_settings}, one row per clinic (SAH-4 row, SAH-7 columns). */
@Repository
public class JdbcFeeSettingsRepository implements FeeSettingsRepository {

    private static final String FIND = """
            select fee_timing, follow_up_days, follow_up_fee, discount_limits::text as discount_limits, upi_on_bill
            from opd.opd_settings where tenant_id = :tenantId
            """;
    private static final String UPSERT = """
            insert into opd.opd_settings
                (tenant_id, fee_timing, follow_up_days, follow_up_fee, discount_limits, upi_on_bill, updated_by)
            values
                (:tenantId, :feeTiming, :followUpDays, :followUpFee, cast(:discountLimits as jsonb), :upiOnBill, :actor)
            on conflict (tenant_id) do update set
                fee_timing = excluded.fee_timing,
                follow_up_days = excluded.follow_up_days,
                follow_up_fee = excluded.follow_up_fee,
                discount_limits = excluded.discount_limits,
                upi_on_bill = excluded.upi_on_bill,
                updated_by = excluded.updated_by,
                updated_at = now(),
                version = opd.opd_settings.version + 1
            """;
    private static final TypeReference<Map<String, Integer>> LIMITS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcFeeSettingsRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<FeeRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(this::map).optional();
    }

    @Override
    public void upsert(UUID tenantId, FeeRow row, UUID actorUserId) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("feeTiming", row.feeTiming())
                .param("followUpDays", row.followUpDays())
                .param("followUpFee", row.followUpFee())
                .param("discountLimits", write(row.discountLimits()))
                .param("upiOnBill", row.upiOnBill())
                .param("actor", actorUserId)
                .update();
    }

    private FeeRow map(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new FeeRow(rs.getString("fee_timing"), rs.getInt("follow_up_days"), rs.getString("follow_up_fee"),
                    objectMapper.readValue(rs.getString("discount_limits"), LIMITS), rs.getBoolean("upi_on_bill"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("opd_settings.discount_limits is not valid JSON", ex);
        }
    }

    private String write(Map<String, Integer> limits) {
        try {
            return objectMapper.writeValueAsString(limits);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Discount limits could not be written as JSON", ex);
        }
    }
}
