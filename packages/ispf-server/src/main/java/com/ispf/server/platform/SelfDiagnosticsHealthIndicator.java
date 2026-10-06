package com.ispf.server.platform;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports self-diagnostics bootstrap. Contributor name is {@code selfDiagnostics}
 * and is part of the readiness health group.
 */
@Component
public class SelfDiagnosticsHealthIndicator implements HealthIndicator {

    private final PlatformSelfDiagnosticsBootstrap bootstrap;

    public SelfDiagnosticsHealthIndicator(PlatformSelfDiagnosticsBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public Health health() {
        if (bootstrap.isContourReady()) {
            return Health.up().build();
        }
        return Health.down().withDetail("error", bootstrap.bootstrapFailure()).build();
    }
}
