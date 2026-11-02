package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.application.RolePermissionRepository.Cell;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-6 AC13, "Who can do what": the owner column is locked on, the owner can tick other roles, and
 * a change applies straight away. Only the cells that differ from today's matrix are written.
 */
@Service
public class RolePermissionService {

    static final Set<String> PERMISSIONS =
            Set.of("token", "vitals", "collect", "cancel", "refund", "revenue", "export");

    private final RolePermissionRepository rolePermissionRepository;
    private final TeamRepository teamRepository;
    private final AuditLog auditLog;

    public RolePermissionService(RolePermissionRepository rolePermissionRepository, TeamRepository teamRepository,
                                  AuditLog auditLog) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.teamRepository = teamRepository;
        this.auditLog = auditLog;
    }

    /** Any signed-in clinic user may read the matrix (non-owners see it view-only). */
    @Transactional(readOnly = true)
    public List<Cell> get() {
        return rolePermissionRepository.effective();
    }

    @Transactional
    public List<Cell> save(UUID tenantId, UUID actorUserId, List<Cell> requested) {
        boolean owner = teamRepository.findByUser(tenantId, actorUserId)
                .map(row -> row.role() == MembershipRole.OWNER && row.status() == MembershipStatus.ACTIVE)
                .orElse(false);
        if (!owner) {
            throw new AccessRefusedException("Only the clinic owner can change who can do what.");
        }
        for (Cell cell : requested) {
            if (!PERMISSIONS.contains(cell.permission())) {
                throw new TeamInputInvalidException("Choose a valid permission");
            }
            if (cell.role() == MembershipRole.OWNER && !cell.allowed()) {
                throw new TeamInputInvalidException("The owner always keeps every permission.");
            }
        }
        Map<String, Boolean> current = new HashMap<>();
        for (Cell cell : rolePermissionRepository.effective()) {
            current.put(key(cell.role(), cell.permission()), cell.allowed());
        }
        Map<String, Cell> changes = new LinkedHashMap<>();
        for (Cell cell : requested) {
            String key = key(cell.role(), cell.permission());
            Boolean today = current.get(key);
            if (today != null && today != cell.allowed()) {
                changes.put(key, cell);
            } else {
                changes.remove(key);
            }
        }
        if (!changes.isEmpty()) {
            List<Map<String, Object>> before = new ArrayList<>();
            List<Map<String, Object>> after = new ArrayList<>();
            for (Cell cell : changes.values()) {
                rolePermissionRepository.upsert(cell.role(), cell.permission(), cell.allowed(), actorUserId);
                before.add(cellValues(cell, !cell.allowed()));
                after.add(cellValues(cell, cell.allowed()));
            }
            auditLog.record("ROLE_PERMISSIONS_UPDATED", "ROLE_PERMISSION", null,
                    Map.of("cells", before), Map.of("cells", after));
        }
        return rolePermissionRepository.effective();
    }

    private static String key(MembershipRole role, String permission) {
        return role.name() + "/" + permission;
    }

    private static Map<String, Object> cellValues(Cell cell, boolean allowed) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("role", cell.role().name());
        values.put("permission", cell.permission());
        values.put("allowed", allowed);
        return values;
    }
}
