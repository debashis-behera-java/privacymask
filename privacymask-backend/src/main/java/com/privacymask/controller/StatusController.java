package com.privacymask.controller;

import com.privacymask.dto.StatusResponse;
import com.privacymask.service.StatusService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Phase 1 public API: gateway health/status.
 */
@RestController
@RequestMapping(path = "/api/v1/privacymask", produces = MediaType.APPLICATION_JSON_VALUE)
public class StatusController {

    private final StatusService statusService;

    public StatusController(StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * Returns gateway status. Fully non-blocking.
     */
    @GetMapping("/status")
    public Mono<StatusResponse> status() {
        return statusService.currentStatus();
    }
}
