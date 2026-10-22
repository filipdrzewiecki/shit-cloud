package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.data.ExportView;
import com.drzewiecki.shitcloud.migration.MigrationService;
import com.drzewiecki.shitcloud.migration.MigrationUndo;
import com.drzewiecki.shitcloud.migration.MigrationView;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Data changes the build chat applied to an app: list them, undo the latest while its copy is kept, download the
 * documents as they were before. Applying one happens with the app's Replace HTML ({@code AppController}).
 */
@RestController
@PreAuthorize("hasRole('BUILDER')")
public class MigrationController {

    private final MigrationService migrations;

    public MigrationController(MigrationService migrations) {
        this.migrations = migrations;
    }

    @GetMapping("/api/apps/{slug}/migrations")
    public List<MigrationView> list(@PathVariable String slug) {
        return migrations.list(slug);
    }

    @PostMapping("/api/migrations/{id}/undo")
    public MigrationUndo undo(@PathVariable long id) {
        return migrations.undo(id, AuthenticationUtils.currentUser().id());
    }

    @GetMapping("/api/migrations/{id}/copy")
    public ResponseEntity<ExportView> copy(@PathVariable long id) {
        ExportView copy = migrations.copy(id);
        ContentDisposition file = ContentDisposition.attachment()
                .filename(copy.app().slug() + "-before-change-" + id + ".json")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, file.toString())
                .body(copy);
    }
}
