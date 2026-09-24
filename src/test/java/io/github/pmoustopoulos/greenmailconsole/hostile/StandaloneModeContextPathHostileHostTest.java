package io.github.pmoustopoulos.greenmailconsole.hostile;

import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler;
import io.github.pmoustopoulos.greenmailconsole.MailConsoleHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Standalone mode inside the hostile host, under server.servlet.context-path=/api. */
@SpringBootTest(
        classes = HostileHostApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "greenmail.console.enabled=true",
                "greenmail.console.smtp-port=0",
                "greenmail.console.mode=standalone",
                "greenmail.console.port=0",
                "server.servlet.context-path=/api"
        })
class StandaloneModeContextPathHostileHostTest extends AbstractHostileHostTest {

    @Autowired
    MailConsoleHttpServer server;

    @Autowired
    MailConsoleHandler handler;

    @Autowired
    ApplicationContext context;

    @Override
    String consoleUrl() {
        return "http://localhost:" + server.getPort() + "/mail-console";
    }

    @Override
    String expectedBasePath() {
        return "/mail-console"; // own server: the host's context path does not apply
    }

    @Override
    void assertNonLoopbackClientIsRejected() {
        MailConsoleHandler.ConsoleResponse response = handler.handle(new MailConsoleHandler.ConsoleRequest(
                "GET", "/api/messages", "/mail-console", "203.0.113.7"));
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void nothingIsRegisteredInTheHostServletContext() throws Exception {
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .noneMatch(registration -> registration.getFilter().getClass().getName().contains("MailConsole"));
        assertThat(server.getPort()).isNotEqualTo(appPort);
        // On the app port the console path is just another host URL, guarded by the host's security.
        assertThat(send("GET", hostUrl("/mail-console")).statusCode()).isEqualTo(401);
    }
}
