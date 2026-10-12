package com.example.inventory.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.sync.SyncDtos.SyncChanges;

@RestController
public class SyncController {

    private final SyncQueries sync;

    @Value("${app.sync.page-size:500}")
    private int pageSize = 500;

    public SyncController(SyncQueries sync) {
        this.sync = sync;
    }

    @GetMapping("/sync/changes")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    @Transactional(readOnly = true)
    public SyncChanges changes(@RequestParam(required = false) String since) {
        return sync.changesSince(SyncToken.decode(since), pageSize);
    }
}
