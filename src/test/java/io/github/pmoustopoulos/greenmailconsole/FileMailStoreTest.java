package io.github.pmoustopoulos.greenmailconsole;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class FileMailStoreTest {

    private static final Session SESSION = Session.getInstance(new Properties());

    @TempDir
    Path dir;

    private MimeMessage message(String subject) throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setFrom(new InternetAddress("from@localhost"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("to@localhost"));
        message.setSubject(subject);
        message.setText("body of " + subject);
        message.saveChanges();
        return message;
    }

    @Test
    void persistsAndReadsBackWithArrivalAndContent() throws Exception {
        FileMailStore store = new FileMailStore(dir);
        Instant arrival = Instant.ofEpochMilli(1_700_000_000_000L);

        store.persist(message("Hello"), arrival, "mid:<a@localhost>");

        List<FileMailStore.PersistedMail> all = store.readAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).arrival()).isEqualTo(arrival);
        assertThat(all.get(0).message().getSubject()).isEqualTo("Hello");
    }

    @Test
    void skipsAMessageWhoseKeyIsAlreadyStored() throws Exception {
        FileMailStore store = new FileMailStore(dir);

        assertThat(store.existsForKey("mid:<dup@localhost>")).isFalse();
        store.persist(message("First"), Instant.now(), "mid:<dup@localhost>");
        assertThat(store.existsForKey("mid:<dup@localhost>")).isTrue();

        // Same key again must not create a second file
        store.persist(message("Second"), Instant.now(), "mid:<dup@localhost>");
        assertThat(store.readAll()).hasSize(1);
    }

    @Test
    void readsBackInChronologicalOrder() throws Exception {
        FileMailStore store = new FileMailStore(dir);
        store.persist(message("Newer"), Instant.ofEpochMilli(2000), "mid:<2@localhost>");
        store.persist(message("Older"), Instant.ofEpochMilli(1000), "mid:<1@localhost>");

        List<FileMailStore.PersistedMail> all = store.readAll();
        assertThat(all).extracting(m -> m.message().getSubject()).containsExactly("Older", "Newer");
    }

    @Test
    void deleteByKeyRemovesOnlyThatMessage() throws Exception {
        FileMailStore store = new FileMailStore(dir);
        store.persist(message("Keep"), Instant.now(), "mid:<keep@localhost>");
        store.persist(message("Drop"), Instant.now(), "mid:<drop@localhost>");

        store.deleteByKey("mid:<drop@localhost>");

        List<FileMailStore.PersistedMail> all = store.readAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).message().getSubject()).isEqualTo("Keep");
    }

    @Test
    void clearRemovesEverything() throws Exception {
        FileMailStore store = new FileMailStore(dir);
        store.persist(message("One"), Instant.now(), "mid:<1@localhost>");
        store.persist(message("Two"), Instant.now(), "mid:<2@localhost>");

        store.clear();

        assertThat(store.readAll()).isEmpty();
    }
}
