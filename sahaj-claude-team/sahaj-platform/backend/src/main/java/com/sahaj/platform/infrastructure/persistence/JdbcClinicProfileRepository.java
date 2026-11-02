package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.ClinicProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.clinic_profile}: one row per tenant, {@code tenant_id} as the primary key (SAH-4, SAH-7). */
@Repository
public class JdbcClinicProfileRepository implements ClinicProfileRepository {

    private static final String UPSERT = """
            insert into platform.clinic_profile
                (tenant_id, name, address, city, pin, phone, registration_no, specialty_code, brand, default_language)
            values
                (:tenantId, :name, :address, :city, :pin, :phone, :registrationNo, :specialtyCode, :brand, :defaultLanguage)
            on conflict (tenant_id) do update set
                name = excluded.name,
                address = excluded.address,
                city = excluded.city,
                pin = excluded.pin,
                phone = excluded.phone,
                registration_no = excluded.registration_no,
                specialty_code = excluded.specialty_code,
                brand = excluded.brand,
                default_language = excluded.default_language
            """;

    private static final String FIND_CITY = "select city from platform.clinic_profile where tenant_id = :tenantId";

    private static final String FIND = """
            select p.name, p.name_local, p.address, p.city, p.pin, p.phone, p.whatsapp, p.maps_url,
                   p.registration_no, t.slug, p.specialty_code, p.brand, p.brand_hex
            from platform.clinic_profile p
            join platform.tenant t on t.id = p.tenant_id
            where p.tenant_id = :tenantId
            """;

    private static final String UPDATE = """
            update platform.clinic_profile
            set name = :name, name_local = :nameLocal, address = :address, city = :city, pin = :pin,
                phone = :phone, whatsapp = :whatsapp, maps_url = :mapsUrl, registration_no = :registrationNo,
                brand = :brand, brand_hex = :brandHex,
                updated_by = :actor, updated_at = now(), version = version + 1
            where tenant_id = :tenantId
            """;

    private static final String SET_SPECIALTY = """
            update platform.clinic_profile
            set specialty_code = :code, updated_by = :actor, updated_at = now(), version = version + 1
            where tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;

    public JdbcClinicProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findCity(UUID tenantId) {
        return jdbc.sql(FIND_CITY).param("tenantId", tenantId).query(String.class).optional();
    }

    @Override
    public Optional<ProfileRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(JdbcClinicProfileRepository::map).optional();
    }

    @Override
    public void update(UUID tenantId, ProfileRow row, UUID actorUserId) {
        jdbc.sql(UPDATE)
                .param("tenantId", tenantId)
                .param("name", row.name())
                .param("nameLocal", row.nameLocal())
                .param("address", row.address())
                .param("city", row.city())
                .param("pin", row.pin())
                .param("phone", row.phone())
                .param("whatsapp", row.whatsapp())
                .param("mapsUrl", row.mapsUrl())
                .param("registrationNo", row.registrationNo())
                .param("brand", row.brand())
                .param("brandHex", row.brandHex())
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public void setSpecialty(UUID tenantId, String specialtyCode, UUID actorUserId) {
        jdbc.sql(SET_SPECIALTY).param("tenantId", tenantId).param("code", specialtyCode)
                .param("actor", actorUserId).update();
    }

    @Override
    public void upsert(UUID tenantId, String name, String address, String city, String pin, String phone,
                        String registrationNo, String specialtyCode, String brand, String defaultLanguage) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("address", address)
                .param("city", city)
                .param("pin", pin)
                .param("phone", phone)
                .param("registrationNo", registrationNo)
                .param("specialtyCode", specialtyCode)
                .param("brand", brand)
                .param("defaultLanguage", defaultLanguage)
                .update();
    }

    private static ProfileRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileRow(rs.getString("name"), rs.getString("name_local"), rs.getString("address"),
                rs.getString("city"), trimmed(rs.getString("pin")), rs.getString("phone"), rs.getString("whatsapp"),
                rs.getString("maps_url"), rs.getString("registration_no"), rs.getString("slug"),
                rs.getString("specialty_code"), rs.getString("brand"), trimmed(rs.getString("brand_hex")));
    }

    /** {@code pin} and {@code brand_hex} are fixed-width columns: never hand back padding. */
    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
