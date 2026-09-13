package io.github.pmoustopoulos.greenmailconsole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.nio.file.Paths;

/**
 * Logs the GreenMail mail-console URL (with the actual port and any servlet
 * context path) once the embedded web server is ready, so it is easy to find
 * in the startup output.
 */
public class MailConsoleStartupLogger implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(MailConsoleStartupLogger.class);

    private final GreenMailConsoleProperties properties;
    private final Environment environment;

    public MailConsoleStartupLogger(GreenMailConsoleProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String consoleUrl = "http://localhost:" + port + contextPath + properties.getPath();

        String storage = properties.getStorage() == GreenMailConsoleProperties.Storage.FILE
                ? "file: " + Paths.get(properties.getDirectory()).toAbsolutePath().normalize()
                : "in-memory (cleared on restart)";

        log.info("");
        log.info("----------------------------------------------------------------");
        log.info("  GreenMail mail console:   {}", consoleUrl);
        log.info("  SMTP listening:            localhost:{}", properties.getSmtpPort());
        log.info("  Storage:                   {}", storage);
        log.info("----------------------------------------------------------------");
        log.info("");
    }
}
