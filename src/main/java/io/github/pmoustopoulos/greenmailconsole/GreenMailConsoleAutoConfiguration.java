package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Paths;

@AutoConfiguration
@EnableConfigurationProperties(GreenMailConsoleProperties.class)
@ConditionalOnProperty(prefix = "greenmail.console", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GreenMailConsoleAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public GreenMail greenMailServer(GreenMailConsoleProperties properties) {
        ServerSetup setup = new ServerSetup(
                properties.getSmtpPort(), null, ServerSetup.PROTOCOL_SMTP);
        return new GreenMail(setup);
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
    public MailConsoleController mailConsoleController(
            MailConsoleService mailConsoleService, GreenMailConsoleProperties properties) {
        return new MailConsoleController(mailConsoleService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MailConsoleStartupLogger mailConsoleStartupLogger(
            GreenMailConsoleProperties properties, Environment environment) {
        return new MailConsoleStartupLogger(properties, environment);
    }
}
