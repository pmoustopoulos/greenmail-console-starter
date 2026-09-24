package io.github.pmoustopoulos.greenmailconsole;

import io.github.pmoustopoulos.greenmailconsole.dto.MailAttachmentContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport-agnostic request handling for the mail console: routing, JSON, parts and deletes.
 *
 * <p>Both transports — the servlet {@link MailConsoleFilter} (same port as the host app) and the
 * standalone {@link MailConsoleHttpServer} (its own port) — translate their native request into a
 * {@link ConsoleRequest}, call {@link #handle}, and write the returned {@link ConsoleResponse}.
 * Nothing here touches Spring MVC or the host's beans: JSON is written with a private
 * {@link JsonMapper}, so a customised host {@code ObjectMapper}/{@code JsonMapper} cannot change
 * the console's wire format.
 *
 * <p>Routes, relative to the console base path:
 * <pre>
 *   GET    ""                                  console HTML page
 *   GET    /api/messages                       list of MailSummary
 *   DELETE /api/messages                       purge all
 *   GET    /api/messages/{id}                  MailDetail (404 if missing)
 *   DELETE /api/messages/{id}                  delete one (404 if missing)
 *   GET    /api/messages/{id}/parts/{partId}   raw part bytes (404 if missing)
 * </pre>
 */
public class MailConsoleHandler {

    private static final Logger log = LoggerFactory.getLogger(MailConsoleHandler.class);

    private static final String JSON = "application/json";
    private static final String HTML = "text/html;charset=UTF-8";

    private final MailConsoleService service;
    private final boolean allowRemote;
    // Library-owned mapper: deliberately NOT the host's bean, which may be customised
    // (naming strategy, root wrapping, ...) and would change the JSON the UI expects.
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private volatile String cachedTemplate;

    public MailConsoleHandler(MailConsoleService service, boolean allowRemote) {
        this.service = service;
        this.allowRemote = allowRemote;
    }

    /**
     * An incoming console request.
     *
     * @param method        HTTP method, e.g. {@code GET}
     * @param path          raw (still URL-encoded) path relative to the console base path, e.g.
     *                      {@code ""} or {@code /api/messages/0}
     * @param basePath      absolute base path the browser uses to reach the console (including any
     *                      servlet context path); injected into the HTML page
     * @param remoteAddress IP address of the client
     */
    public record ConsoleRequest(String method, String path, String basePath, String remoteAddress) {
    }

    /** A fully buffered response; {@code body} is never null. */
    public record ConsoleResponse(int status, Map<String, String> headers, byte[] body) {
    }

    public ConsoleResponse handle(ConsoleRequest request) {
        if (!allowRemote && !isLoopback(request.remoteAddress())) {
            return empty(403);
        }
        try {
            return route(request);
        } catch (Exception ex) {
            log.warn("GreenMail console: failed to handle {} {}", request.method(), request.path(), ex);
            return empty(500);
        }
    }

    private ConsoleResponse route(ConsoleRequest request) {
        String method = request.method();
        String path = request.path();
        if (path.isEmpty() || path.equals("/")) {
            return method.equals("GET") ? html(request.basePath()) : methodNotAllowed("GET");
        }

        String[] segments = segments(path);
        if (segments.length < 2 || !segments[0].equals("api") || !segments[1].equals("messages")) {
            return empty(404);
        }
        switch (segments.length) {
            case 2 -> {
                return switch (method) {
                    case "GET" -> json(service.listMessages());
                    case "DELETE" -> {
                        service.clearMessages();
                        yield empty(204);
                    }
                    default -> methodNotAllowed("GET, DELETE");
                };
            }
            case 3 -> {
                String id = segments[2];
                return switch (method) {
                    case "GET" -> service.getMessage(id).map(this::json).orElseGet(() -> empty(404));
                    case "DELETE" -> empty(service.deleteMessage(id) ? 204 : 404);
                    default -> methodNotAllowed("GET, DELETE");
                };
            }
            case 5 -> {
                if (!segments[3].equals("parts")) {
                    return empty(404);
                }
                if (!method.equals("GET")) {
                    return methodNotAllowed("GET");
                }
                return service.getAttachmentContent(segments[2], segments[4])
                        .map(this::part)
                        .orElseGet(() -> empty(404));
            }
            default -> {
                return empty(404);
            }
        }
    }

    /** Splits {@code /a/b%20c/} into decoded {@code [a, b c]}; empty segments are dropped. */
    private static String[] segments(String path) {
        return Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .map(s -> UriUtils.decode(s, StandardCharsets.UTF_8))
                .toArray(String[]::new);
    }

    private ConsoleResponse html(String basePath) {
        String page = template().replace("__BASE_PATH__", basePath);
        return new ConsoleResponse(200, headers(HTML), page.getBytes(StandardCharsets.UTF_8));
    }

    private String template() {
        String template = cachedTemplate;
        if (template == null) {
            try (var in = new ClassPathResource("greenmail-console/index.html").getInputStream()) {
                template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                cachedTemplate = template;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load GreenMail console page", ex);
            }
        }
        return template;
    }

    private ConsoleResponse json(Object value) {
        return new ConsoleResponse(200, headers(JSON), jsonMapper.writeValueAsBytes(value));
    }

    private ConsoleResponse part(MailAttachmentContent content) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(content.contentType());
        } catch (Exception ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        // Inline parts (e.g. cid: images) render in place; others download.
        ContentDisposition disposition = ContentDisposition
                .builder(content.inline() ? "inline" : "attachment")
                .filename(content.filename())
                .build();
        Map<String, String> headers = headers(mediaType.toString());
        headers.put("Content-Disposition", disposition.toString());
        return new ConsoleResponse(200, headers, content.data());
    }

    private static ConsoleResponse methodNotAllowed(String allow) {
        Map<String, String> headers = headers(null);
        headers.put("Allow", allow);
        return new ConsoleResponse(405, headers, new byte[0]);
    }

    private static ConsoleResponse empty(int status) {
        return new ConsoleResponse(status, headers(null), new byte[0]);
    }

    private static Map<String, String> headers(String contentType) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        // Captured mail can contain secrets (reset links, tokens): never let it be cached.
        headers.put("Cache-Control", "no-store");
        return headers;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            // Remote addresses are IP literals, so this never triggers a DNS lookup.
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    /** Normalises a configured base path to {@code /x/y} form (leading slash, no trailing slash). */
    static String normalizePath(String path) {
        String p = (path == null || path.isBlank()) ? "/mail-console" : path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
