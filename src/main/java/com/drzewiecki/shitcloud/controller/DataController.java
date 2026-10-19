package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.data.DataQuery;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.data.DocView;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * App data, called by the host page on behalf of the app it shows (apps themselves never reach the API).
 * Everyone signed in reads and writes, guests included: they are the people apps are made for.
 */
@RestController
@RequestMapping("/api/data/{space}/{collection}")
@PreAuthorize("isAuthenticated()")
public class DataController {

    private final DataService dataService;

    public DataController(DataService dataService) {
        this.dataService = dataService;
    }

    @PostMapping("/query")
    public List<DocView> query(@PathVariable String space, @PathVariable String collection,
                               @RequestBody(required = false) DataQuery query) {
        return dataService.query(space, collection, query);
    }

    @PutMapping("/{id}")
    public DocView set(@PathVariable String space, @PathVariable String collection, @PathVariable String id,
                       @RequestBody DataWrite write) {
        return dataService.set(space, collection, id, write, caller());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocView add(@PathVariable String space, @PathVariable String collection, @RequestBody DataWrite write) {
        return dataService.add(space, collection, write, caller());
    }

    @DeleteMapping("/{id}")
    public Deleted remove(@PathVariable String space, @PathVariable String collection, @PathVariable String id,
                          @RequestParam(required = false) Long expectedVersion) {
        return new Deleted(dataService.remove(space, collection, id, expectedVersion, caller()));
    }

    public record Deleted(boolean deleted) {
    }

    private static String caller() {
        return AuthenticationUtils.currentUser().id();
    }
}
