package com.arbbot.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardServer {

  private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);

  private final int port;
  private final SnapshotAssembler assembler;
  private final ObjectMapper mapper;
  private final CopyOnWriteArrayList<OutputStream> clients = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private ScheduledExecutorService pusher;

  public DashboardServer(int port, SnapshotAssembler assembler) {
    this.port = port;
    this.assembler = assembler;
    this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
  }

  public void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

    // Serve index.html
    server.createContext(
        "/",
        exchange -> {
          if (!"/".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            return;
          }
          try (InputStream html =
              getClass().getClassLoader().getResourceAsStream("dashboard/index.html")) {
            if (html == null) {
              exchange.sendResponseHeaders(404, -1);
              return;
            }
            byte[] body = html.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          }
        });

    // One-shot snapshot
    server.createContext(
        "/api/snapshot",
        exchange -> {
          exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
          if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
          }
          byte[] body = mapper.writeValueAsBytes(assembler.buildSnapshot());
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });

    // SSE stream
    server.createContext(
        "/api/stream",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.getResponseHeaders().set("Cache-Control", "no-cache");
          exchange.getResponseHeaders().set("Connection", "keep-alive");
          exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          clients.add(out);
          try {
            // Park the virtual thread until the client disconnects (notified by pusher on error)
            synchronized (out) {
              out.wait();
            }
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            clients.remove(out);
            try {
              out.close();
            } catch (IOException ignored) {
            }
          }
        });

    server.start();

    // Pusher: build snapshot and broadcast to all SSE clients every 200ms
    pusher =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sse-pusher").factory());
    pusher.scheduleAtFixedRate(
        () -> {
          try {
            String json = mapper.writeValueAsString(assembler.buildSnapshot());
            byte[] msg = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            for (OutputStream out : clients) {
              try {
                out.write(msg);
                out.flush();
              } catch (IOException ex) {
                clients.remove(out);
                synchronized (out) {
                  out.notifyAll();
                }
              }
            }
          } catch (Exception e) {
            log.debug("SSE push error: {}", e.getMessage());
          }
        },
        0,
        200,
        TimeUnit.MILLISECONDS);

    log.info("Dashboard started on port {}", port);
  }

  public void stop() {
    if (pusher != null) {
      pusher.shutdown();
    }
    if (server != null) {
      server.stop(1);
    }
  }
}
