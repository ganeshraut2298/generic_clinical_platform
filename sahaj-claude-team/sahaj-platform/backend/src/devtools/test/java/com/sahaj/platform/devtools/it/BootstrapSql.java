package com.sahaj.platform.devtools.it;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.UUID;

/**
 * The five bootstrap functions of ADR-018 Revision 3 as plain calls, exactly the way the application calls them
 * ({@code sahaj_app} connection, caller commits). Used by the function-level and the HTTP-level tests that need to claim a try
 * without judging it (R3-I4, R3-I13) or to judge it by hand (R3-I7, R3-I12).
 *
 * <p>Result shapes (docs/database/SAH-42.md): {@code begin} returns {@code (outcome, failures, locked_until)};
 * {@code unlock_failed} {@code (failures, locked_until)}; {@code unlock_succeeded} the ticket expiry; {@code ticket_status}
 * {@code (status, locked_until)}; {@code register} {@code (outcome, locked_until)}. No value of any secret is built here.
 */
final class BootstrapSql {

    /** {@code outcome} is CLOSED, LOCKED, BUSY or COUNTED. */
    record Begin(String outcome, int failures, Timestamp lockedUntil) {
    }

    record Failed(int failures, Timestamp lockedUntil) {
    }

    record Status(String status, Timestamp lockedUntil) {
    }

    record Registered(String outcome, Timestamp lockedUntil) {
    }

    private BootstrapSql() {
    }

    static Begin begin(Connection c, UUID tryId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select outcome, failures, locked_until from platform.admin_bootstrap_begin(?)")) {
            if (tryId == null) {
                s.setNull(1, Types.OTHER);
            } else {
                s.setObject(1, tryId);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Begin(rs.getString(1), rs.getInt(2), rs.getTimestamp(3));
            }
        }
    }

    static Failed unlockFailed(Connection c, UUID tryId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "select failures, locked_until from platform.admin_bootstrap_unlock_failed(?, ?::inet, ?)")) {
            s.setObject(1, tryId);
            s.setString(2, "127.0.0.1");
            s.setString(3, "qa-request");
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Failed(rs.getInt(1), rs.getTimestamp(2));
            }
        }
    }

    static Timestamp unlockSucceeded(Connection c, UUID tryId, String ticket, String binding) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select platform.admin_bootstrap_unlock_succeeded(?, ?, ?)")) {
            s.setObject(1, tryId);
            s.setString(2, ticket);
            s.setString(3, binding);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    static Status ticketStatus(Connection c, String ticket, String binding) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select status, locked_until from platform.admin_bootstrap_ticket_status(?, ?)")) {
            s.setString(1, ticket);
            s.setString(2, binding);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Status(rs.getString(1), rs.getTimestamp(2));
            }
        }
    }

    static Registered register(Connection c, String ticket, String binding, UUID id, String email, String name, String hash,
                               String request) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "select outcome, locked_until from platform.admin_bootstrap_register(?, ?, ?, ?, ?, ?, ?::inet, ?)")) {
            s.setString(1, ticket);
            s.setString(2, binding);
            if (id == null) {
                s.setNull(3, Types.OTHER);
            } else {
                s.setObject(3, id);
            }
            s.setString(4, email);
            s.setString(5, name);
            s.setString(6, hash);
            s.setString(7, "127.0.0.1");
            s.setString(8, request);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Registered(rs.getString(1), rs.getTimestamp(2));
            }
        }
    }

    /** One claim as the application commits it: {@code begin}, commit, nothing else. Returns what {@code begin} returned. */
    static Begin claimAndCommit(UUID tryId) throws SQLException {
        try (Connection c = com.sahaj.platform.it.devsample.SampleAdminDb.app()) {
            c.setAutoCommit(false);
            Begin result = begin(c, tryId);
            c.commit();
            return result;
        }
    }
}
