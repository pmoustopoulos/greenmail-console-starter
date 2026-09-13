package io.github.pmoustopoulos.greenmailconsole;

import io.github.pmoustopoulos.greenmailconsole.dto.MailSummary;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailFilePersistenceTest {

    @TempDir
    Path dir;

    /** A "session": a GreenMail server + service + persistence all sharing the given directory. */
    private record Session(GreenMail greenMail, MailConsoleService service, MailFilePersistence persistence) {
        void stop() {
            persistence.stop();
            greenMail.stop();
        }
    }

    private Session boot() throws Exception {
        FileMailStore store = new FileMailStore(dir);
        GreenMail greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        MailConsoleService service = new MailConsoleService(greenMail, store);
        // Duration.ZERO disables the background poller so the test drives capture deterministically.
        MailFilePersistence persistence = new MailFilePersistence(greenMail, store, service, Duration.ZERO);
        persistence.start(); // reload from disk (empty on first boot)
        return new Session(greenMail, service, persistence);
    }

    @Test
    void capturedMailSurvivesARestart() throws Exception {
        Session first = boot();
        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "Persisted subject", "hello body", first.greenMail().getSmtp().getServerSetup());

        first.persistence().flush(); // capture to disk
        assertThat(first.service().listMessages()).hasSize(1);
        String arrivalBefore = first.service().listMessages().get(0).receivedAt();
        first.stop();

        // Restart: brand-new server/service/persistence on the same directory
        Session second = boot();
        List<MailSummary> summaries = second.service().listMessages();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).subject()).isEqualTo("Persisted subject");
        // Original arrival time is restored (not reset to reload time)
        assertThat(summaries.get(0).receivedAt()).isEqualTo(arrivalBefore);
        second.stop();
    }

    @Test
    void deletingAMessageRemovesItPermanently() throws Exception {
        Session first = boot();
        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "Doomed", "bye", first.greenMail().getSmtp().getServerSetup());
        first.persistence().flush();

        String id = first.service().listMessages().get(0).id();
        assertThat(first.service().deleteMessage(id)).isTrue();
        first.stop();

        // After a restart the deleted message must not come back
        Session second = boot();
        assertThat(second.service().listMessages()).isEmpty();
        second.stop();
    }
}
