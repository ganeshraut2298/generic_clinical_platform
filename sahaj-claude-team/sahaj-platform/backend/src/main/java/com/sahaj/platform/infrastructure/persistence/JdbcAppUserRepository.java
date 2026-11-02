package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.AppUserRepository;
import com.sahaj.platform.domain.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code platform.app_user}: no row level security, reached only through SAH-3's narrow services. */
@Repository
public class JdbcAppUserRepository implements AppUserRepository {

    private static final String FIND_BY_MOBILE = """
            select id, mobile, full_name from platform.app_user where mobile = :mobile
            """;
    private static final String FIND_BY_ID = """
            select id, mobile, full_name from platform.app_user where id = :id
            """;
    private static final String INSERT = """
            insert into platform.app_user (id, mobile, full_name) values (:id, :mobile, :fullName)
            """;

    private final JdbcClient jdbc;

    public JdbcAppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AppUser> findByMobile(String mobile) {
        return jdbc.sql(FIND_BY_MOBILE).param("mobile", mobile).query(JdbcAppUserRepository::map).optional();
    }

    @Override
    public Optional<AppUser> findById(UUID userId) {
        return jdbc.sql(FIND_BY_ID).param("id", userId).query(JdbcAppUserRepository::map).optional();
    }

    @Override
    public void insert(UUID id, String mobile, String fullName) {
        jdbc.sql(INSERT).param("id", id).param("mobile", mobile).param("fullName", fullName).update();
    }

    private static AppUser map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AppUser(rs.getObject("id", UUID.class), rs.getString("mobile"), rs.getString("full_name"));
    }
}
