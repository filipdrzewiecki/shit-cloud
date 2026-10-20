package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.ExportView;
import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.AppUsage;
import com.drzewiecki.shitcloud.model.DeleteResult;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.model.ReplaceHtmlRequest;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The app registry. Everyone signed in lists, opens and downloads apps; builders deploy and manage any app
 * (archive, restore, replace the HTML, delete with its data).
 */
@RestController
@RequestMapping("/api/apps")
@PreAuthorize("isAuthenticated()")
public class AppController {

    private final AppRegistry appRegistry;
    private final DataService dataService;

    public AppController(AppRegistry appRegistry, DataService dataService) {
        this.appRegistry = appRegistry;
        this.dataService = dataService;
    }

    @GetMapping
    public List<AppSummary> list() {
        return appRegistry.list();
    }

    @GetMapping("/{slug}")
    public AppDetail open(@PathVariable String slug) {
        return appRegistry.open(slug);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BUILDER')")
    public AppSummary deploy(@RequestBody DeployRequest request) {
        return appRegistry.deploy(request, caller());
    }

    @PostMapping("/{slug}/archive")
    @PreAuthorize("hasRole('BUILDER')")
    public AppSummary archive(@PathVariable String slug) {
        return appRegistry.archive(slug, caller());
    }

    @PostMapping("/{slug}/restore")
    @PreAuthorize("hasRole('BUILDER')")
    public AppSummary restore(@PathVariable String slug) {
        return appRegistry.restore(slug);
    }

    @PutMapping("/{slug}/html")
    @PreAuthorize("hasRole('BUILDER')")
    public AppSummary replaceHtml(@PathVariable String slug, @RequestBody ReplaceHtmlRequest request) {
        return appRegistry.replaceHtml(slug, request.html(), caller());
    }

    /** Irreversible: the app, its versions, documents and history. {@code confirm} must repeat the slug. */
    @DeleteMapping("/{slug}")
    @PreAuthorize("hasRole('BUILDER')")
    public DeleteResult delete(@PathVariable String slug, @RequestParam(required = false) String confirm) {
        return appRegistry.delete(slug, confirm, caller());
    }

    @GetMapping("/{slug}/usage")
    public AppUsage usage(@PathVariable String slug) {
        return appRegistry.usage(slug);
    }

    @GetMapping("/{slug}/export")
    public ResponseEntity<ExportView> export(@PathVariable String slug) {
        ContentDisposition file = ContentDisposition.attachment().filename(slug + "-data.json").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, file.toString())
                .body(dataService.export(slug));
    }

    private static String caller() {
        return AuthenticationUtils.currentUser().id();
    }
}
