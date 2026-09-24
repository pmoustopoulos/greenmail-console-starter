package io.github.pmoustopoulos.greenmailconsole.hostile;

import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the console inside {@link HostileHostApplication} over real HTTP and checks that every
 * console feature works untouched by the host's interceptors, advice, JSON mapper, filters and
 * security — and that the host's own endpoints are still protected.
 *
 * <p>Requests are sent with no Referer, no Authorization and no custom headers, exactly like a
 * developer typing the console URL into a browser.
 */
abstract class AbstractHostileHostTest {

    private final HttpClient http = HttpClient.newHttpClient();
    // Plain test-side mapper, only for reading responses.
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int appPort;

    @Autowired
    GreenMail greenMail;

    @Autowired
    Environment environment;

    /** Absolute URL of the console root, e.g. {@code http://localhost:1234/api/mail-console}. */
    abstract String consoleUrl();

    /** Base path the page must inject for its JS API calls. */
    abstract String expectedBasePath();

    /** Asserts that a non-loopback client gets 403 while {@code allow-remote=false}. */
    abstract void assertNonLoopbackClientIsRejected() throws Exception;

    String contextPath() {
        return environment.getProperty("server.servlet.context-path", "");
    }

    String hostUrl(String path) {
        return "http://localhost:" + appPort + contextPath() + path;
    }

    HttpResponse<byte[]> send(String method, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    JsonNode readJson(HttpResponse<byte[]> response) {
        return json.readTree(response.body());
    }

    /** A real JavaMailSender pointed at the embedded SMTP server (on whatever port it got). */
    JavaMailSender mailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(greenMail.getSmtp().getPort());
        return sender;
    }

    @BeforeEach
    void clearMailbox() throws Exception {
        assertThat(send("DELETE", consoleUrl() + "/api/messages").statusCode()).isEqualTo(204);
    }

    @Test
    void servesConsolePageWithBasePath() throws Exception {
        HttpResponse<byte[]> response = send("GET", consoleUrl());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
        assertThat(text(response)).contains("const BASE = '" + expectedBasePath() + "'");
    }

    @Test
    void fullApiFlowUsesTheLibrarysOwnJsonShape() throws Exception {
        JavaMailSender sender = mailSender();

        SimpleMailMessage simple = new SimpleMailMessage();
        simple.setFrom("from@localhost");
        simple.setTo("to@localhost");
        simple.setSubject("Plain subject");
        simple.setText("Plain body");
        sender.send(simple);

        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom("from@localhost");
        helper.setTo("to@localhost");
        helper.setSubject("Attachment subject");
        helper.setText("attachment body", false);
        helper.addAttachment("notes.txt",
                new ByteArrayResource("hello world".getBytes(StandardCharsets.UTF_8)), "text/plain");
        sender.send(mime);

        // List: a bare camelCase array — not snake_case, not root-wrapped, not {"wrapped":...}
        HttpResponse<byte[]> list = send("GET", consoleUrl() + "/api/messages");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(list.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode messages = readJson(list);
        assertThat(messages.isArray()).as("body: %s", text(list)).isTrue();
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).has("receivedAt")).isTrue();
        assertThat(messages.get(0).has("received_at")).isFalse();

        String attachmentId = null;
        String plainId = null;
        for (JsonNode message : messages) {
            if (message.get("subject").asString().equals("Attachment subject")) {
                attachmentId = message.get("id").asString();
            } else {
                plainId = message.get("id").asString();
            }
        }
        assertThat(attachmentId).isNotNull();
        assertThat(plainId).isNotNull();

        // Detail
        HttpResponse<byte[]> detail = send("GET", consoleUrl() + "/api/messages/" + attachmentId);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode detailJson = readJson(detail);
        assertThat(detailJson.get("subject").asString()).isEqualTo("Attachment subject");
        assertThat(detailJson.get("attachments").size()).isEqualTo(1);
        JsonNode attachment = detailJson.get("attachments").get(0);
        assertThat(attachment.get("filename").asString()).isEqualTo("notes.txt");
        assertThat(attachment.get("contentType").asString()).isEqualTo("text/plain");

        // Part bytes
        HttpResponse<byte[]> part = send("GET", consoleUrl() + "/api/messages/" + attachmentId
                + "/parts/" + attachment.get("id").asString());
        assertThat(part.statusCode()).isEqualTo(200);
        assertThat(part.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        assertThat(part.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"notes.txt\"");
        assertThat(text(part)).isEqualTo("hello world");

        // 404 / 405
        assertThat(send("GET", consoleUrl() + "/api/messages/999").statusCode()).isEqualTo(404);
        assertThat(send("GET", consoleUrl() + "/api/messages/999/parts/0").statusCode()).isEqualTo(404);
        assertThat(send("GET", consoleUrl() + "/unknown").statusCode()).isEqualTo(404);
        assertThat(send("POST", consoleUrl() + "/api/messages").statusCode()).isEqualTo(405);

        // Delete one, then purge
        assertThat(send("DELETE", consoleUrl() + "/api/messages/" + plainId).statusCode()).isEqualTo(204);
        assertThat(readJson(send("GET", consoleUrl() + "/api/messages")).size()).isEqualTo(1);
        assertThat(send("DELETE", consoleUrl() + "/api/messages").statusCode()).isEqualTo(204);
        assertThat(readJson(send("GET", consoleUrl() + "/api/messages")).size()).isEqualTo(0);
    }

    @Test
    void hostEndpointsStayProtected() throws Exception {
        HttpResponse<byte[]> response = send("GET", hostUrl("/some-host-endpoint"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(HostileHostApplication.ENTRY_POINT_HEADER)).hasValue("true");
        assertThat(text(response)).doesNotContain("host data");
    }

    @Test
    void nonLoopbackClientsAreRejected() throws Exception {
        assertNonLoopbackClientIsRejected();
    }
}
