package com.example.signer.server.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/health} — public reachability check for the desktop app
 * (replaces the old "log in as probe/probe" check). Reveals only that the
 * server answers, the API version and the oldest desktop version it accepts:
 * no build, host, database, user or configuration details.
 */
@RestController
public class HealthController {

    /** Bumped when the desktop-facing API changes incompatibly. */
    public static final int API_VERSION = 1;

    private final String minDesktopVersion;

    public HealthController(
            @Value("${signature.remote-server.desktop.min-version:0.1.0-SNAPSHOT}") String minDesktopVersion) {
        this.minDesktopVersion = minDesktopVersion;
    }

    @GetMapping("/api/health")
    public ApiContracts.HealthView health() {
        return new ApiContracts.HealthView("UP", API_VERSION, minDesktopVersion);
    }
}
