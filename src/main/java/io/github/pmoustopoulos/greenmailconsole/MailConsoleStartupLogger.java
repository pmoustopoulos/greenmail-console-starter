package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.nio.file.Paths;

/**
 * Logs a WARN banner with the GreenMail mail-console URL (with the actual port and any servlet
 * context path), mode, bind address and SMTP port once the embedded web server is ready, so it is
 * easy to find in the startup output — and impossible to miss if it is ever enabled by mistake.
 */
public class MailConsoleStartupLogger implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(MailConsoleStartupLogger.class);

    private final GreenMail greenMail;
    private final GreenMailConsoleProperties properties;
    private final Environment environment;
    // Null in filter mode.
    private final MailConsoleHttpServer standaloneServer;

    public MailConsoleStartupLogger(GreenMail greenMail, GreenMailConsoleProperties properties,
                                    Environment environment, MailConsoleHttpServer standaloneServer) {
        this.greenMail = greenMail;
        this.properties = properties;
        this.environment = environment;
        this.standaloneServer = standaloneServer;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() != null) {
            return; // e.g. the actuator's separate management server; log once, for the main server
        }

        String mode;
        String consoleUrl;
        String bindAddress;
        if (standaloneServer != null) {
            mode = "standalone (own HTTP server)";
            consoleUrl = "http://" + displayHost(standaloneServer.getBindAddress()) + ":"
                    + standaloneServer.getPort() + standaloneServer.getPath();
            bindAddress = standaloneServer.getBindAddress();
        } else {
            mode = "filter (app port, ahead of the app's security/MVC)";
            String contextPath = environment.getProperty("server.servlet.context-path", "");
            consoleUrl = "http://localhost:" + event.getWebServer().getPort() + contextPath
                    + MailConsoleHandler.normalizePath(properties.getPath());
            bindAddress = "app server (" + environment.getProperty("server.address", "all interfaces") + ")";
        }

        String storage = properties.getStorage() == GreenMailConsoleProperties.Storage.FILE
                ? "file: " + Paths.get(properties.getDirectory()).toAbsolutePath().normalize()
                : "in-memory (cleared on restart)";
        String access = properties.isAllowRemote() ? "any client (allow-remote=true)" : "localhost only";

        int smtpPort = greenMail.getSmtp().getPort();

        log.warn("");
        log.warn("----------------------------------------------------------------");
        log.warn("  GreenMail mail console -- DEV/TEST ONLY, never enable in production");
        log.warn("  Console URL:               {}", consoleUrl);
        log.warn("  Mode:                      {}", mode);
        log.warn("  Bind address:              {}", bindAddress);
        log.warn("  Access:                    {}", access);
        log.warn("  SMTP listening:            localhost:{}", smtpPort);
        log.warn("  Point your app at it:      spring.mail.host=localhost  spring.mail.port={}", smtpPort);
        log.warn("  Storage:                   {}", storage);
        log.warn("----------------------------------------------------------------");
        log.warn("");
    }

    private static String displayHost(String bindAddress) {
        return bindAddress == null || bindAddress.isBlank() || bindAddress.equals("0.0.0.0")
                || bindAddress.equals("127.0.0.1") ? "localhost" : bindAddress;
    }
}
