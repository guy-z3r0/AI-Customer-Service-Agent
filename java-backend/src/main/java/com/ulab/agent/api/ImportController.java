package com.ulab.agent.api;

import com.ulab.agent.api.dto.ImportResultView;
import com.ulab.agent.services.LegacyImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pulls the version-1 JSON files under java-backend/data/businesses/ into the
 * database. The import also runs once at start-up; this endpoint exists so it
 * can be repeated after dropping new folders in, without a restart.
 *
 * Re-running is safe: a business already in the database is left alone.
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final LegacyImportService importer;

    public ImportController(LegacyImportService importer) {
        this.importer = importer;
    }

    @PostMapping("/legacy")
    public ImportResultView importLegacy() {
        return importer.importAll();
    }
}
