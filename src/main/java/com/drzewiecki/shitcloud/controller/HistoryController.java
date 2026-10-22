package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.data.CollectionSummary;
import com.drzewiecki.shitcloud.data.DataException;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DocHistory;
import com.drzewiecki.shitcloud.data.DocPage;
import com.drzewiecki.shitcloud.data.DocView;
import com.drzewiecki.shitcloud.data.HistoryService;
import com.drzewiecki.shitcloud.model.RestoreRequest;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Data & history page: an app's stored documents, removed ones included, each one's earlier states, and putting
 * one back. Builders only — history shows who changed what.
 */
@RestController
@RequestMapping("/api/apps/{slug}/data")
@PreAuthorize("hasRole('BUILDER')")
public class HistoryController {

    private final HistoryService history;
    private final DataService data;

    public HistoryController(HistoryService history, DataService data) {
        this.history = history;
        this.data = data;
    }

    @GetMapping
    public List<CollectionSummary> collections(@PathVariable String slug) {
        return history.collections(slug);
    }

    @GetMapping("/{collection}")
    public DocPage documents(@PathVariable String slug, @PathVariable String collection,
                             @RequestParam(defaultValue = "false") boolean removed,
                             @RequestParam(required = false) String prefix,
                             @RequestParam(required = false) Integer offset,
                             @RequestParam(required = false) Integer limit) {
        return history.documents(slug, collection, removed, prefix, offset, limit);
    }

    @GetMapping("/{collection}/{id}/history")
    public DocHistory history(@PathVariable String slug, @PathVariable String collection, @PathVariable String id) {
        return history.history(slug, collection, id);
    }

    @PostMapping("/{collection}/{id}/restore")
    public DocView restore(@PathVariable String slug, @PathVariable String collection, @PathVariable String id,
                           @RequestBody RestoreRequest request) {
        if (request.historyId() == null || request.expectedVersion() == null) {
            throw new DataException(HttpStatus.BAD_REQUEST, DataException.BAD_REQUEST,
                    "historyId and expectedVersion are required");
        }
        return data.restore(slug, collection, id, request.historyId(), request.expectedVersion(),
                AuthenticationUtils.currentUser().id());
    }
}
