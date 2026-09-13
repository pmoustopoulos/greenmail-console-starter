package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

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
                    assertThat(context).hasSingleBean(MailConsoleController.class);
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
            assertThat(context).doesNotHaveBean(MailConsoleController.class);
        });
    }
}
