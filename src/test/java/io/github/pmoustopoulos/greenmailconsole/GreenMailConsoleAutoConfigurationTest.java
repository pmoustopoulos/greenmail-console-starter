package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GreenMailConsoleAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GreenMailConsoleAutoConfiguration.class));

    @Test
    void bindsPropertiesWithDefaultsAndOverrides() {
        runner.withPropertyValues(
                        "greenmail.console.enabled=true",
                        "greenmail.console.smtp-port=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(GreenMailConsoleProperties.class);
                    GreenMailConsoleProperties props = context.getBean(GreenMailConsoleProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getPath()).isEqualTo("/mail-console");
                    assertThat(props.getSmtpPort()).isEqualTo(0);
                });
    }

    @Test
    void registersBeansWhenEnabled() {
        // smtp-port=0 lets GreenMail pick a free port, avoiding conflicts during the test
        runner.withPropertyValues("greenmail.console.enabled=true", "greenmail.console.smtp-port=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(GreenMail.class);
                    assertThat(context).hasSingleBean(MailConsoleService.class);
                    assertThat(context).hasSingleBean(MailConsoleHandler.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void registersFileStoreBeansWhenStorageIsFile(@TempDir Path dir) {
        runner.withPropertyValues(
                        "greenmail.console.enabled=true",
                        "greenmail.console.smtp-port=0",
                        "greenmail.console.storage=file",
                        "greenmail.console.persist-interval=0s",
                        "greenmail.console.directory=" + dir.resolve("mail-data"))
                .run(context -> {
                    assertThat(context).hasSingleBean(FileMailStore.class);
                    assertThat(context).hasSingleBean(MailFilePersistence.class);
                    GreenMailConsoleProperties props = context.getBean(GreenMailConsoleProperties.class);
                    assertThat(props.getStorage()).isEqualTo(GreenMailConsoleProperties.Storage.FILE);
                });
    }

    @Test
    void doesNotRegisterFileStoreBeansInMemoryMode() {
        runner.withPropertyValues("greenmail.console.enabled=true", "greenmail.console.smtp-port=0")
                .run(context -> {
                    assertThat(context).hasSingleBean(MailConsoleService.class);
                    assertThat(context).doesNotHaveBean(FileMailStore.class);
                    assertThat(context).doesNotHaveBean(MailFilePersistence.class);
                });
    }

    @Test
    void registersNothingWhenDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(GreenMail.class);
            assertThat(context).doesNotHaveBean(MailConsoleService.class);
            assertThat(context).doesNotHaveBean(MailConsoleHandler.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context).doesNotHaveBean(MailConsoleHttpServer.class);
        });
    }

    @Test
    void standaloneModeFallsBackToFreePortAndStopsWithContext() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busyPort = busy.getLocalPort();
            AtomicReference<MailConsoleHttpServer> serverRef = new AtomicReference<>();
            runner.withPropertyValues(
                            "greenmail.console.enabled=true",
                            "greenmail.console.smtp-port=0",
                            "greenmail.console.mode=standalone",
                            "greenmail.console.port=" + busyPort)
                    .run(context -> {
                        assertThat(context).hasSingleBean(MailConsoleHttpServer.class);
                        assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                        MailConsoleHttpServer server = context.getBean(MailConsoleHttpServer.class);
                        assertThat(server.isRunning()).isTrue();
                        assertThat(server.getPort()).isPositive().isNotEqualTo(busyPort);
                        serverRef.set(server);
                    });
            MailConsoleHttpServer server = serverRef.get();
            assertThat(server.isRunning()).isFalse();
            assertThat(server.getPort()).isEqualTo(-1);
        }
    }

    @Test
    void filterModeRegistersNoStandaloneServer() {
        runner.withPropertyValues("greenmail.console.enabled=true", "greenmail.console.smtp-port=0")
                .run(context -> assertThat(context).doesNotHaveBean(MailConsoleHttpServer.class));
    }

    @Test
    void failsStartupWhenEnabledUnderAForbiddenProfile() {
        runner.withPropertyValues(
                        "greenmail.console.enabled=true",
                        "greenmail.console.smtp-port=0",
                        "spring.profiles.active=dev,prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("forbidden profile(s) [prod]")
                            .hasMessageContaining("DEV/TEST-ONLY");
                });
    }

    @Test
    void forbiddenProfilesAreConfigurable() {
        runner.withPropertyValues(
                        "greenmail.console.enabled=true",
                        "greenmail.console.smtp-port=0",
                        "greenmail.console.forbidden-profiles=staging",
                        "spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(GreenMail.class));
    }

    @Test
    void forbiddenProfileIsIgnoredWhenDisabled() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(GreenMail.class));
    }
}
