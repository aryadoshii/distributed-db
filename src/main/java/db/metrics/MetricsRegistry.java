package db.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Singleton metrics registry.
 *
 * Uses Micrometer's SimpleMeterRegistry by default — no external
 * infrastructure needed. In production, swap for PrometheusMeterRegistry
 * to get a real /metrics scrape endpoint.
 */
public class MetricsRegistry {

    private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

    private MetricsRegistry() {}

    public static MeterRegistry get() {
        return REGISTRY;
    }

    public static Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(REGISTRY);
    }

    public static Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(REGISTRY);
    }

    /** Dump all meters as Prometheus-compatible text (name{tags} value). */
    public static String scrape() {
        StringBuilder sb = new StringBuilder();
        REGISTRY.getMeters().forEach(meter -> {
            String name = meter.getId().getName().replace('.', '_');
            meter.measure().forEach(m -> {
                sb.append("# ").append(name).append(" ").append(m.getStatistic()).append("\n");
                sb.append(name).append("{").append(tagsToString(meter)).append("} ")
                  .append(m.getValue()).append("\n");
            });
        });
        return sb.toString();
    }

    private static String tagsToString(io.micrometer.core.instrument.Meter meter) {
        StringBuilder sb = new StringBuilder();
        meter.getId().getTags().forEach(tag ->
            sb.append(tag.getKey()).append("=\"").append(tag.getValue()).append("\",")
        );
        if (!sb.isEmpty()) sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
