package db.metrics;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Tiny HTTP server exposing /metrics (Prometheus text) and /health.
 *
 * Uses com.sun.net.httpserver — built into the JDK, no extra deps.
 * Start: MetricsServer.start(9090)
 * Scrape: curl http://localhost:9090/metrics
 */
public class MetricsServer {

    private final HttpServer server;

    public MetricsServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/metrics", exchange -> {
            byte[] bytes = MetricsRegistry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        server.createContext("/health", exchange -> {
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
    }

    public void start() { server.start(); }
    public void stop()  { server.stop(0); }

    public static MetricsServer start(int port) throws IOException {
        MetricsServer ms = new MetricsServer(port);
        ms.start();
        return ms;
    }
}
