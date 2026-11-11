package com.sahaj.ipd.application;

import com.sahaj.ipd.application.PackageRepository.PackageRow;
import com.sahaj.ipd.application.PackageViews.IpdPackage;
import com.sahaj.ipd.application.PackageViews.PackageList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-29 the Packages list of Clinic setup > Admission and deposits. Read-only: nothing here writes a package, an
 * audit row or a record-access row (a package is not patient data). The role check is on the controller; the
 * module state is checked here (Off answers 403 module-off; Trial, On and Read-only may read).
 */
@Service
public class PackageService {

    private final IpdGate gate;
    private final PackageRepository packages;

    PackageService(IpdGate gate, PackageRepository packages) {
        this.gate = gate;
        this.packages = packages;
    }

    @Transactional(readOnly = true)
    public PackageList list(UUID tenantId) {
        gate.check(tenantId, false);
        List<PackageRow> rows = packages.findActive(tenantId);
        return new PackageList(rows.stream()
                .map(r -> new IpdPackage(r.id(), r.name(), r.pricePaise(), r.includedDays())).toList());
    }
}
