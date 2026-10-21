package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.data.DependencyLog;
import com.drzewiecki.shitcloud.data.DependencyView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Which apps used a space's data (its own app included) — for the "Used by" line builders see on an app's page.
 */
@RestController
@PreAuthorize("hasRole('BUILDER')")
public class DepsController {

    private final DependencyLog dependencies;

    public DepsController(DependencyLog dependencies) {
        this.dependencies = dependencies;
    }

    @GetMapping("/api/deps/{space}")
    public List<DependencyView> users(@PathVariable String space) {
        return dependencies.users(space);
    }
}
