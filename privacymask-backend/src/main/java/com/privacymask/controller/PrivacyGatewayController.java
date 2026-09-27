package com.privacymask.controller;

import com.privacymask.dto.AnalyzeRequest;
import com.privacymask.dto.AnalyzeResponse;
import com.privacymask.service.PrivacyGatewayService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Phase 2 privacy gateway: accepts AI requests for processing.
 *
 * <p>Thin by design - validation is declarative ({@code @Valid}), orchestration
 * lives in {@link PrivacyGatewayService}, and privacy processing behind the
 * {@code PrivacyProcessor} extension point.</p>
 */
@RestController
@RequestMapping(path = "/api/v1/privacymask", produces = MediaType.APPLICATION_JSON_VALUE)
public class PrivacyGatewayController {

    private final PrivacyGatewayService gatewayService;

    public PrivacyGatewayController(PrivacyGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * Accepts an analyze request and returns the processed result.
     * Fully non-blocking.
     */
    @PostMapping(path = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request) {
        return gatewayService.analyze(request);
    }
}
