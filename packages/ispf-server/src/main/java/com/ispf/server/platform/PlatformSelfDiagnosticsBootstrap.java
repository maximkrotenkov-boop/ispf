package com.ispf.server.platform;

import com.ispf.core.model.DataRecord;
import com.ispf.core.model.DataSchema;
import com.ispf.core.model.FieldType;
import com.ispf.core.object.ObjectType;
import com.ispf.core.object.PlatformObject;
import com.ispf.server.config.PlatformMetricsProbeProperties;
import com.ispf.server.dashboard.DashboardLayouts;
import com.ispf.server.dashboard.DashboardService;
import com.ispf.server.object.ObjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Seeds first-party self-diagnostics: probe device variables + HMI dashboard on the object tree.
 * ISPF is the primary observability surface; Prometheus/Grafana remain optional export.
 */
@Component
public class PlatformSelfDiagnosticsBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlatformSelfDiagnosticsBootstrap.class);

    public static final String DASHBOARD_PATH = "root.platform.dashboards.platform-metrics";

    private static final DataSchema INTEGER_VALUE = DataSchema.builder("integerValue")
            .field("value", FieldType.INTEGER)
            .build();
    private static final DataSchema DOUBLE_VALUE = DataSchema.builder("doubleValue")
            .field("value", FieldType.DOUBLE)
            .build();

    private record ProbeVar(String name, boolean floating, boolean history) {}

    private static final List<ProbeVar> PROBE_VARS = List.of(
            new ProbeVar("eventHistoryRecords", false, true),
            new ProbeVar("eventsPerSecond", true, false),
            new ProbeVar("alertFiresTotal", false, true),
            new ProbeVar("alertFiresPerSecond", true, false),
            new ProbeVar("objectChangeQueueSize", false, true),
            new ProbeVar("eventJournalQueueSize", false, true),
            new ProbeVar("variableHistoryQueueSize", false, true),
            new ProbeVar("objectChangeDroppedTotal", false, false),
            new ProbeVar("telemetryCoalesceDropsTotal", false, true),
            new ProbeVar("telemetryBindingBypassTotal", false, true),
            new ProbeVar("telemetryHistorianOnlyTotal", false, true),
            new ProbeVar("websocketClients", false, true),
            new ProbeVar("heapUsedMb", true, false),
            new ProbeVar("activeConnections", false, false),
            new ProbeVar("threadsAwaitingConnection", false, false),
            new ProbeVar("activeDrivers", false, false),
            new ProbeVar("workflowInstancesRunning", false, false),
            new ProbeVar("variableHistorySamples", false, false)
    );

    private final PlatformMetricsProbeProperties properties;
    private final ObjectManager objectManager;
    private final DashboardService dashboardService;
    private final PlatformMetricsProbeService probeService;

    /** Set when an enabled bootstrap fails. Empty means the contour is ready or not required. */
    private volatile String bootstrapFailure = "";

    public PlatformSelfDiagnosticsBootstrap(
            PlatformMetricsProbeProperties properties,
            ObjectManager objectManager,
            DashboardService dashboardService,
            PlatformMetricsProbeService probeService
    ) {
        this.properties = properties;
        this.objectManager = objectManager;
        this.dashboardService = dashboardService;
        this.probeService = probeService;
    }

    /**
     * Runs early so the probe device exists before later startup. A failure is recorded for
     * health and readiness. It must not be thrown: this listener is {@code @Order(40)}, and an
     * exception aborts {@code ApplicationReadyEvent} before the object tree is marked ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(40)
    public void ensureSelfDiagnostics() {
        if (!properties.isEnsureOnStartup()) {
            return;
        }
        try {
            ensureProbeDevice();
            ensureProbeVariables();
            ensureDashboard();
            if (properties.isEnabled()) {
                probeService.setDiagnosticsProbeEnabled(true);
            }
            bootstrapFailure = "";
            log.info(
                    "Self-diagnostics ready: device={} dashboard={} (sync={})",
                    PlatformMetricsProbeService.DEVICE_PATH,
                    DASHBOARD_PATH,
                    properties.isEnabled()
            );
        } catch (RuntimeException ex) {
            bootstrapFailure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.error("Self-diagnostics bootstrap failed, readiness is down, startup continues: {}", bootstrapFailure);
        }
    }

    public boolean isContourReady() {
        return bootstrapFailure.isEmpty();
    }

    public String bootstrapFailure() {
        return bootstrapFailure;
    }

    private void ensureProbeDevice() {
        if (objectManager.tree().findByPath(PlatformMetricsProbeService.DEVICE_PATH).isPresent()) {
            return;
        }
        objectManager.create(
                "root.platform.devices",
                "platform-metrics-probe",
                ObjectType.DEVICE,
                "Platform Metrics Probe",
                "Mirrors platform metrics for first-party self-diagnostic dashboards.",
                null
        );
    }

    private void ensureProbeVariables() {
        String path = PlatformMetricsProbeService.DEVICE_PATH;
        for (ProbeVar var : PROBE_VARS) {
            PlatformObject node = objectManager.require(path);
            if (node.getVariable(var.name()).isPresent()) {
                continue;
            }
            DataSchema schema = var.floating() ? DOUBLE_VALUE : INTEGER_VALUE;
            // Do not use a numeric ternary for zeros: Java promotes Integer/Double branches to double,
            // so INTEGER DataRecord rejects the boxed Double ("value must be integer").
            Object zeroValue;
            if (var.floating()) {
                zeroValue = 0.0;
            } else {
                zeroValue = 0;
            }
            DataRecord zero = DataRecord.single(schema, Map.of("value", zeroValue));
            try {
                objectManager.createVariable(
                        path,
                        var.name(),
                        schema,
                        true,
                        true,
                        zero,
                        var.history(),
                        var.history() ? 1 : null
                );
            } catch (IllegalArgumentException ignored) {
                // race / already exists
            }
        }
    }

    private void ensureDashboard() {
        boolean created = objectManager.tree().findByPath(DASHBOARD_PATH).isEmpty();
        if (created) {
            objectManager.create(
                    "root.platform.dashboards",
                    "platform-metrics",
                    ObjectType.DASHBOARD,
                    "Platform Self-Diagnostics",
                    "First-party hot-path and runtime health (platform-metrics-probe).",
                    "dashboard-v1"
            );
        }
        dashboardService.ensureDashboardStructure(DASHBOARD_PATH);
        PlatformObject dashboard = objectManager.require(DASHBOARD_PATH);
        String layout = dashboard.getVariable("layout")
                .flatMap(v -> v.value())
                .filter(record -> !record.rows().isEmpty())
                .map(record -> String.valueOf(record.rows().get(0).get("value")))
                .orElse("");
        boolean emptyLayout = layout.isBlank()
                || layout.contains("\"widgets\":[]")
                || layout.contains("\"widgets\": []");
        if (created || emptyLayout) {
            DataSchema stringValue = DataSchema.builder("stringValue").field("value", FieldType.STRING).build();
            DataSchema intValue = DataSchema.builder("integerValue").field("value", FieldType.INTEGER).build();
            dashboard.setVariableValue(
                    "title",
                    DataRecord.single(stringValue, Map.of("value", "Platform Self-Diagnostics"))
            );
            dashboard.setVariableValue(
                    "refreshIntervalMs",
                    DataRecord.single(intValue, Map.of("value", 5000))
            );
            dashboard.setVariableValue(
                    "layout",
                    DataRecord.single(
                            stringValue,
                            Map.of("value", DashboardLayouts.PLATFORM_SELF_DIAGNOSTICS_DASHBOARD.trim())
                    )
            );
            objectManager.persistNodeTree(DASHBOARD_PATH);
        }
    }
}
