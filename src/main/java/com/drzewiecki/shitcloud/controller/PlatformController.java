package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.apps.AppOrigin;
import com.drzewiecki.shitcloud.model.Platform;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings the GUI needs from the server, e.g. where draft previews load the library mirror from.
 */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("isAuthenticated()")
public class PlatformController {

    private final AppOrigin appOrigin;

    public PlatformController(AppOrigin appOrigin) {
        this.appOrigin = appOrigin;
    }

    @GetMapping
    public Platform platform() {
        return new Platform(appOrigin.libraryBaseUrl());
    }
}
