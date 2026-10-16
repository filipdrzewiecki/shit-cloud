package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.model.AppDetail;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/apps")
public class AppController {

    private final AppStore appStore;

    public AppController(AppStore appStore) {
        this.appStore = appStore;
    }

    @GetMapping
    public List<AppSummary> list() {
        return appStore.list();
    }

    @GetMapping("/{slug}")
    public AppDetail open(@PathVariable String slug) {
        return appStore.open(slug);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppSummary deploy(@RequestBody DeployRequest request) {
        return appStore.deploy(request, MeController.MOCK_USER.displayName());
    }
}
