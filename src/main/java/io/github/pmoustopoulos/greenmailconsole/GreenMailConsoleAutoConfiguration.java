package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Paths;

@AutoConfiguration
@EnableConfigurationProperties(GreenMailConsoleProperties.class)
@ConditionalOnProperty(prefix = "greenmail.console", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GreenMailConsoleAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GreenMailConsoleAutoConfiguration.class);

    /**
     * Starts the embedded GreenMail SMTP server, preferring the configured port
     * ({@code greenmail.console.smtp-port}, default 3025; use {@code 0} to let the OS pick a free one).
     *
     * <p>The starter deliberately does <em>not</em> register a {@code JavaMailSender}. Your
     * application keeps using Spring Boot's standard mail configuration: point
     * {@code spring.mail.host}/{@code spring.mail.port} at this server (host {@code localhost}, port =
     * the port logged below) to have the console capture outgoing mail. Swapping to a real mail server
     * is then just a normal change to {@code spring.mail.*} — there is no {@code @Primary} sender to
     * fight and nothing starter-specific to undo.
     *
     * <p>To stay robust ("plug and play, nothing breaks"), if the preferred port is already in use —
     * by another process, or by another application context in the same JVM during a test run — the
     * server falls back to an OS-assigned free port instead of failing startup. When that happens a
     * {@code WARN} is logged so that, if you rely on capturing mail in dev, you can reconcile your
     * {@code spring.mail.port} (or free the preferred port). In the common case the preferred port is
     * free, so it stays stable and matches your configuration.
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public GreenMail greenMailServer(GreenMailConsoleProperties properties) {
        int preferred = properties.getSmtpPort();

        // Probe first so we never hand GreenMail an occupied port (which would log a noisy
        // BindException from its server thread); fall back to an OS-assigned free port if busy.
        int port = Ports.isFree(InetAddress.getLoopbackAddress(), preferred) ? preferred : Ports.findFree();

        GreenMail greenMail = tryStart(port);
        if (greenMail == null) {
            // Rare race: the port was taken between the probe and the bind. Try one more free port.
            port = Ports.findFree();
            greenMail = tryStart(port);
            if (greenMail == null) {
                throw new IllegalStateException(
                        "GreenMail console: could not start the embedded SMTP server on a free port.");
            }
        }

        int actual = greenMail.getSmtp().getPort();
        if (preferred != 0 && actual != preferred) {
            log.warn("GreenMail console: SMTP port {} was busy (another process or application context "
                    + "is using it); started on free port {} instead. To capture outgoing mail, set "
                    + "'spring.mail.port={}' (or free port {} and restart).", preferred, actual, actual, preferred);
        } else {
            log.info("GreenMail console: embedded SMTP server started on port {}. "
                    + "Point spring.mail.host=localhost and spring.mail.port={} to capture outgoing mail.",
                    actual, actual);
        }
        return greenMail;
    }

    /** Attempts to start GreenMail on the given SMTP port; returns null if it cannot be bound. */
    private GreenMail tryStart(int port) {
        ServerSetup setup = new ServerSetup(port, null, ServerSetup.PROTOCOL_SMTP);
        GreenMail greenMail = new GreenMail(setup);
        try {
            greenMail.start();
            return greenMail;
        } catch (Exception ex) {
            try {
                greenMail.stop();
            } catch (Exception ignored) {
                // best effort: the server never fully started
            }
            return null;
        }
    }

    /** On-disk mirror of captured mail; registered only when storage=file. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "greenmail.console", name = "storage", havingValue = "file")
    public FileMailStore fileMailStore(GreenMailConsoleProperties properties) throws IOException {
        return new FileMailStore(Paths.get(properties.getDirectory()));
    }

    @Bean
    @ConditionalOnMissingBean
    public MailConsoleService mailConsoleService(
            GreenMail greenMailServer, ObjectProvider<FileMailStore> fileMailStore) {
        // getIfAvailable() is null in memory mode, leaving the service's behaviour unchanged.
        return new MailConsoleService(greenMailServer, fileMailStore.getIfAvailable());
    }

    /**
     * Reloads persisted mail on startup and captures new mail to disk while running. Depends on
     * greenMailServer so it starts after the SMTP server; registered only when storage=file.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "greenmail.console", name = "storage", havingValue = "file")
    public MailFilePersistence mailFilePersistence(
            GreenMail greenMailServer, FileMailStore fileMailStore,
            MailConsoleService mailConsoleService, GreenMailConsoleProperties properties) {
        return new MailFilePersistence(
                greenMailServer, fileMailStore, mailConsoleService, properties.getPersistInterval());
    }

    @Bean
    @ConditionalOnMissingBean
    public MailConsoleHandler mailConsoleHandler(
            MailConsoleService mailConsoleService, GreenMailConsoleProperties properties) {
        return new MailConsoleHandler(mailConsoleService, properties.isAllowRemote());
    }

    /**
     * Default mode: serve the console from a servlet filter on the app's own port, registered ahead
     * of Spring Security ({@code -100}) and every host filter, so the host's security, interceptors,
     * advice, converters and filters never touch console requests.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "greenmail.console", name = "mode", havingValue = "filter", matchIfMissing = true)
    static class FilterModeConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "mailConsoleFilterRegistration")
        FilterRegistrationBean<MailConsoleFilter> mailConsoleFilterRegistration(
                MailConsoleHandler mailConsoleHandler, GreenMailConsoleProperties properties) {
            String path = MailConsoleHandler.normalizePath(properties.getPath());
            FilterRegistrationBean<MailConsoleFilter> registration =
                    new FilterRegistrationBean<>(new MailConsoleFilter(mailConsoleHandler, path));
            registration.setName("greenMailConsoleFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns(path, path + "/*");
            return registration;
        }
    }

    /**
     * Opt-in mode: serve the console from a small library-owned HTTP server on its own port. Nothing
     * is registered in the host's servlet context.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "greenmail.console", name = "mode", havingValue = "standalone")
    static class StandaloneModeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MailConsoleHttpServer mailConsoleHttpServer(
                MailConsoleHandler mailConsoleHandler, GreenMailConsoleProperties properties) {
            return new MailConsoleHttpServer(mailConsoleHandler, properties.getPath(),
                    properties.getBindAddress(), properties.getPort());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public MailConsoleStartupLogger mailConsoleStartupLogger(
            GreenMail greenMailServer, GreenMailConsoleProperties properties, Environment environment,
            ObjectProvider<MailConsoleHttpServer> standaloneServer) {
        return new MailConsoleStartupLogger(
                greenMailServer, properties, environment, standaloneServer.getIfAvailable());
    }
}
