package io.github.pmoustopoulos.greenmailconsole;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler.ConsoleRequest;
import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler.ConsoleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone mode: serves the console from a small library-owned HTTP server (the JDK's built-in
 * {@link HttpServer}) on its own port, completely separate from the host's servlet container.
 *
 * <p>Tied to the Spring context lifecycle: it starts during context refresh (before the host's web
 * server) and stops when the context closes. If the preferred port is busy — another process, or
 * another application context in the same JVM during a test run — it falls back to a free port and
 * logs it, just like the SMTP port.
 */
public class MailConsoleHttpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MailConsoleHttpServer.class);

    private final MailConsoleHandler handler;
    private final String path;
    private final String bindAddress;
    private final int preferredPort;

    private volatile HttpServer server;
    private volatile ExecutorService executor;

    public MailConsoleHttpServer(MailConsoleHandler handler, String path, String bindAddress, int preferredPort) {
        this.handler = handler;
        this.path = MailConsoleHandler.normalizePath(path);
        this.bindAddress = bindAddress;
        this.preferredPort = preferredPort;
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        InetAddress address = resolve(bindAddress);
        int port = Ports.isFree(address, preferredPort) ? preferredPort : Ports.findFree();
        HttpServer created;
        try {
            created = bind(address, port);
        } catch (BindException ex) {
            // Rare race: the port was taken between the probe and the bind. Try one more free port.
            try {
                created = bind(address, Ports.findFree());
            } catch (IOException retry) {
                throw new IllegalStateException("GreenMail console: could not start the standalone console server", retry);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("GreenMail console: could not start the standalone console server", ex);
        }

        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "greenmail-console-http-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        server = created;

        int actual = getPort();
        if (preferredPort != 0 && actual != preferredPort) {
            log.warn("GreenMail console: console port {} was busy; standalone console started on free port {} instead.",
                    preferredPort, actual);
        }
    }

    private static HttpServer bind(InetAddress address, int port) throws IOException {
        return HttpServer.create(new InetSocketAddress(address, port), 0);
    }

    private static InetAddress resolve(String bindAddress) {
        try {
            return InetAddress.getByName(bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress);
        } catch (IOException ex) {
            throw new IllegalStateException("GreenMail console: invalid bind-address '" + bindAddress + "'", ex);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String uri = exchange.getRequestURI().getRawPath();
            ConsoleResponse result;
            if (uri.equals(path) || uri.startsWith(path + "/")) {
                result = handler.handle(new ConsoleRequest(
                        exchange.getRequestMethod(),
                        uri.substring(path.length()),
                        path,
                        exchange.getRemoteAddress().getAddress().getHostAddress()));
            } else {
                result = new ConsoleResponse(404, Map.of(), new byte[0]);
            }
            result.headers().forEach(exchange.getResponseHeaders()::set);
            byte[] body = result.body();
            // -1 tells HttpServer there is no body (required for 204 and friends).
            exchange.sendResponseHeaders(result.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        HttpServer running = server;
        if (running == null) {
            return;
        }
        server = null;
        running.stop(0);
        executor.shutdownNow();
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    /** Start before (and stop after) the host's embedded web server. */
    @Override
    public int getPhase() {
        return 0;
    }

    /** The actual port the console listens on, or {@code -1} if not running. */
    public int getPort() {
        HttpServer running = server;
        return running == null ? -1 : running.getAddress().getPort();
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public String getPath() {
        return path;
    }
}
