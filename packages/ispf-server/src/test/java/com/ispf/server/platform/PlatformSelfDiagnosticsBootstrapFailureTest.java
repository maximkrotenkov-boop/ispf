package com.ispf.server.platform;

import com.ispf.core.object.ObjectTree;
import com.ispf.server.config.PlatformMetricsProbeProperties;
import com.ispf.server.dashboard.DashboardService;
import com.ispf.server.object.ObjectManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformSelfDiagnosticsBootstrapFailureTest {

    @Mock
    PlatformMetricsProbeProperties properties;
    @Mock
    ObjectManager objectManager;
    @Mock
    DashboardService dashboardService;
    @Mock
    PlatformMetricsProbeService probeService;

    @Test
    void bootstrapFailureMarksReadinessDownAndDoesNotThrow() {
        when(properties.isEnsureOnStartup()).thenReturn(true);
        ObjectTree tree = mock(ObjectTree.class);
        when(objectManager.tree()).thenReturn(tree);
        when(tree.findByPath(anyString())).thenThrow(new IllegalStateException("tree down"));
        PlatformSelfDiagnosticsBootstrap bootstrap = bootstrap();
        SelfDiagnosticsHealthIndicator health = new SelfDiagnosticsHealthIndicator(bootstrap);

        assertThatCode(bootstrap::ensureSelfDiagnostics).doesNotThrowAnyException();

        assertThat(bootstrap.isContourReady()).isFalse();
        assertThat(bootstrap.bootstrapFailure()).isEqualTo("tree down");
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("error", "tree down");
    }

    @Test
    void disabledBootstrapStaysReady() {
        when(properties.isEnsureOnStartup()).thenReturn(false);
        PlatformSelfDiagnosticsBootstrap bootstrap = bootstrap();
        SelfDiagnosticsHealthIndicator health = new SelfDiagnosticsHealthIndicator(bootstrap);

        assertThatCode(bootstrap::ensureSelfDiagnostics).doesNotThrowAnyException();

        assertThat(bootstrap.isContourReady()).isTrue();
        assertThat(bootstrap.bootstrapFailure()).isEmpty();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    private PlatformSelfDiagnosticsBootstrap bootstrap() {
        return new PlatformSelfDiagnosticsBootstrap(
                properties,
                objectManager,
                dashboardService,
                probeService
        );
    }
}
