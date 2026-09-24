package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler.ConsoleRequest;
import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler.ConsoleResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MailConsoleHandlerTest {

    private GreenMail greenMail;
    private MailConsoleHandler handler;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        handler = new MailConsoleHandler(new MailConsoleService(greenMail), false);
    }

    @AfterEach
    void tearDown() {
        greenMail.stop();
    }

    private ConsoleResponse call(String method, String path) {
        return call(method, path, "127.0.0.1");
    }

    private ConsoleResponse call(String method, String path, String remote) {
        return handler.handle(new ConsoleRequest(method, path, "/ctx/mail-console", remote));
    }

    private static String body(ConsoleResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void servesHtmlWithBasePath() {
        ConsoleResponse response = call("GET", "");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Content-Type", "text/html;charset=UTF-8");
        assertThat(body(response)).contains("const BASE = '/ctx/mail-console'");
    }

    @Test
    void listsMessagesAsCamelCaseJsonWithNoStore() {
        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "Handler subject", "Handler body", greenMail.getSmtp().getServerSetup());

        ConsoleResponse response = call("GET", "/api/messages");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Content-Type", "application/json")
                .containsEntry("Cache-Control", "no-store");
        assertThat(body(response)).startsWith("[").contains("\"subject\":\"Handler subject\"");

        assertThat(call("GET", "/api/messages/0").status()).isEqualTo(200);
        assertThat(call("GET", "/api/messages/999").status()).isEqualTo(404);
        assertThat(call("DELETE", "/api/messages/0").status()).isEqualTo(204);
        assertThat(call("DELETE", "/api/messages").status()).isEqualTo(204);
    }

    @Test
    void rejectsUnknownPathsAndWrongMethods() {
        assertThat(call("GET", "/nope").status()).isEqualTo(404);
        assertThat(call("GET", "/api/messages/0/nope/0").status()).isEqualTo(404);
        assertThat(call("GET", "/api/messages/0/parts/0/extra").status()).isEqualTo(404);

        ConsoleResponse post = call("POST", "/api/messages");
        assertThat(post.status()).isEqualTo(405);
        assertThat(post.headers()).containsEntry("Allow", "GET, DELETE");
        assertThat(call("PUT", "").status()).isEqualTo(405);
        assertThat(call("DELETE", "/api/messages/0/parts/0").status()).isEqualTo(405);
    }

    @Test
    void rejectsNonLoopbackClientsUnlessAllowed() {
        assertThat(call("GET", "", "10.1.2.3").status()).isEqualTo(403);
        assertThat(call("GET", "", "::1").status()).isEqualTo(200);

        MailConsoleHandler open = new MailConsoleHandler(new MailConsoleService(greenMail), true);
        assertThat(open.handle(new ConsoleRequest("GET", "", "/m", "10.1.2.3")).status()).isEqualTo(200);
    }

    @Test
    void normalizesConfiguredPath() {
        assertThat(MailConsoleHandler.normalizePath("mail/")).isEqualTo("/mail");
        assertThat(MailConsoleHandler.normalizePath("/mail-console")).isEqualTo("/mail-console");
        assertThat(MailConsoleHandler.normalizePath(null)).isEqualTo("/mail-console");
    }
}
