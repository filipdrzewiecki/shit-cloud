package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.data.DataQuery;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.data.DependencyLog;
import com.drzewiecki.shitcloud.data.DocView;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * App data, called by the host page on behalf of the app it shows (apps themselves never reach the API).
 * Everyone signed in reads and writes, guests included: they are the people apps are made for.
 * <p>
 * The host page names the app on screen in {@value #READER}; each successful call is noted in the
 * {@link DependencyLog} under it. Without the header nothing is noted.
 */
@RestController
@RequestMapping("/api/data/{space}/{collection}")
@PreAuthorize("isAuthenticated()")
public class DataController {

    public static final String READER = "X-ShitCloud-App";

    private final DataService dataService;
    private final DependencyLog dependencies;

    public DataController(DataService dataService, DependencyLog dependencies) {
        this.dataService = dataService;
        this.dependencies = dependencies;
    }

    @PostMapping("/query")
    public List<DocView> query(@PathVariable String space, @PathVariable String collection,
                               @RequestBody(required = false) DataQuery query,
                               @RequestHeader(value = READER, required = false) String reader) {
        List<DocView> docs = dataService.query(space, collection, query);
        dependencies.record(reader, space, collection, DependencyLog.Mode.READ);
        return docs;
    }

    @PutMapping("/{id}")
    public DocView set(@PathVariable String space, @PathVariable String collection, @PathVariable String id,
                       @RequestBody DataWrite write, @RequestHeader(value = READER, required = false) String reader) {
        DocView doc = dataService.set(space, collection, id, write, caller());
        dependencies.record(reader, space, collection, DependencyLog.Mode.WRITE);
        return doc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocView add(@PathVariable String space, @PathVariable String collection, @RequestBody DataWrite write,
                       @RequestHeader(value = READER, required = false) String reader) {
        DocView doc = dataService.add(space, collection, write, caller());
        dependencies.record(reader, space, collection, DependencyLog.Mode.WRITE);
        return doc;
    }

    @DeleteMapping("/{id}")
    public Deleted remove(@PathVariable String space, @PathVariable String collection, @PathVariable String id,
                          @RequestParam(required = false) Long expectedVersion,
                          @RequestHeader(value = READER, required = false) String reader) {
        boolean deleted = dataService.remove(space, collection, id, expectedVersion, caller());
        dependencies.record(reader, space, collection, DependencyLog.Mode.WRITE);
        return new Deleted(deleted);
    }

    public record Deleted(boolean deleted) {
    }

    private static String caller() {
        return AuthenticationUtils.currentUser().id();
    }
}
