package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * SAH-92 (DEC-097): a check-in is only possible on the day of the visit, and a booking must be a future slot on the doctor's
 * grid. A test that needs an appointment for today therefore books a later day and moves the row to today with committed
 * SQL (the application clock cannot move), as sahaj_app with the tenant bound.
 */
final class AppointmentToday {

    private AppointmentToday() {
    }

    static void move(DbAccess db, UUID tenantId, String appointmentId) throws SQLException {
        try (Connection c = db.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            try (PreparedStatement u = c.prepareStatement("update opd.appointment set appt_date = ?::date where id = ?::uuid")) {
                u.setString(1, LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
                u.setString(2, appointmentId);
                assertThat(u.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
    }
}
