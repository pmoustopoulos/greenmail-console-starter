package io.github.pmoustopoulos.greenmailconsole;

import io.github.pmoustopoulos.greenmailconsole.dto.MailAttachmentContent;
import io.github.pmoustopoulos.greenmailconsole.dto.MailDetail;
import io.github.pmoustopoulos.greenmailconsole.dto.MailSummary;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Hidden // keep the dev-only console out of the consuming app's springdoc/OpenAPI docs
@RestController
@RequestMapping("${greenmail.console.path:/mail-console}")
public class MailConsoleController {

    private final MailConsoleService service;
    private final GreenMailConsoleProperties properties;

    private volatile String cachedTemplate;

    public MailConsoleController(MailConsoleService service, GreenMailConsoleProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> console(HttpServletRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(renderPage(request.getContextPath()));
    }

    private String renderPage(String contextPath) {
        String template = cachedTemplate;
        if (template == null) {
            try {
                byte[] bytes = new ClassPathResource("greenmail-console/index.html")
                        .getInputStream().readAllBytes();
                template = new String(bytes, StandardCharsets.UTF_8);
                cachedTemplate = template;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load GreenMail console page", ex);
            }
        }
        // Prefix the servlet context path so the console's JS calls resolve correctly
        // whether or not the host app is deployed under a context path. contextPath is
        // "" for a root deployment, preserving the original behaviour.
        String basePath = contextPath + properties.getPath();
        return template.replace("__BASE_PATH__", basePath);
    }

    @GetMapping("/api/messages")
    public List<MailSummary> list() {
        return service.listMessages();
    }

    @GetMapping("/api/messages/{id}")
    public ResponseEntity<MailDetail> get(@PathVariable String id) {
        return service.getMessage(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/messages/{id}/parts/{partId}")
    public ResponseEntity<byte[]> part(@PathVariable String id, @PathVariable String partId) {
        return service.getAttachmentContent(id, partId)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> toResponse(MailAttachmentContent content) {
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
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaType)
                .body(content.data());
    }

    @DeleteMapping("/api/messages/{id}")
    public ResponseEntity<Void> deleteOne(@PathVariable String id) {
        return service.deleteMessage(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/api/messages")
    public ResponseEntity<Void> clear() {
        service.clearMessages();
        return ResponseEntity.noContent().build();
    }
}
