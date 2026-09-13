package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gives {@code storage=file} its persistence. GreenMail has no file backend and no delivery hook,
 * so this component bridges the gap in two directions:
 *
 * <ul>
 *   <li><b>Reload (on startup):</b> reads the {@link FileMailStore}, re-injects each stored message
 *       into GreenMail via a synthetic holding user so the console lists it again, and seeds its
 *       original arrival time back onto the {@link MailConsoleService}.</li>
 *   <li><b>Capture (while running):</b> a single background thread periodically writes newly
 *       received (de-duplicated) messages to the store, so mail is safe even on a crash or kill.</li>
 * </ul>
 *
 * Registered only when {@code greenmail.console.storage=file}; in memory mode it does not exist and
 * behaviour is unchanged.
 */
public class MailFilePersistence {

    private static final Logger log = LoggerFactory.getLogger(MailFilePersistence.class);
    private static final String HOLDING_USER = "console-restore@localhost";

    private final GreenMail greenMail;
    private final FileMailStore store;
    private final MailConsoleService service;
    private final Duration persistInterval;

    private ScheduledExecutorService scheduler;

    public MailFilePersistence(GreenMail greenMail, FileMailStore store,
                               MailConsoleService service, Duration persistInterval) {
        this.greenMail = greenMail;
        this.store = store;
        this.service = service;
        this.persistInterval = persistInterval;
    }

    /** Invoked as the bean init method (after GreenMail has started): reload, then start capturing. */
    public void start() {
        reload();
        if (persistInterval != null && !persistInterval.isZero() && !persistInterval.isNegative()) {
            long millis = persistInterval.toMillis();
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "greenmail-console-persist");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    /** Invoked as the bean destroy method: stop the background thread. */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Re-injects stored messages into GreenMail (oldest first) and restores their arrival times. */
    private void reload() {
        List<FileMailStore.PersistedMail> stored = store.readAll();
        if (stored.isEmpty()) {
            return;
        }
        GreenMailUser user = greenMail.setUser(HOLDING_USER, "console-restore");
        int index = 0;
        for (FileMailStore.PersistedMail mail : stored) {
            try {
                user.deliver(mail.message());
                service.seedArrival(index, mail.arrival());
                index++;
            } catch (Exception ex) {
                log.warn("Failed to reload a stored message: {}", ex.getMessage());
            }
        }
        log.info("Reloaded {} message(s) from {}", index, store.getDirectory());
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (Exception ex) {
            log.warn("Mail capture pass failed: {}", ex.getMessage());
        }
    }

    /** Writes any received, de-duplicated messages that are not yet on disk. */
    void flush() {
        MimeMessage[] messages = greenMail.getReceivedMessages();

        // Collapse per-recipient copies into one representative per message (lowest index wins),
        // using the same key the console uses so the on-disk view matches the console view.
        Map<String, Integer> representatives = new LinkedHashMap<>();
        for (int i = 0; i < messages.length; i++) {
            representatives.putIfAbsent(MailConsoleService.dedupeKey(messages[i], i), i);
        }

        for (Map.Entry<String, Integer> entry : representatives.entrySet()) {
            String key = entry.getKey();
            if (store.existsForKey(key)) {
                continue;
            }
            int idx = entry.getValue();
            // Prefer the arrival the console already recorded so disk and UI agree; otherwise stamp
            // now and seed it back so a later console view shows the same time.
            Instant arrival = service.recordedArrival(idx);
            if (arrival == null) {
                // Millisecond precision so the seeded value round-trips exactly through the
                // filename (which stores epoch millis) on the next reload.
                arrival = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                service.seedArrival(idx, arrival);
            }
            store.persist(messages[idx], arrival, key);
        }
    }
}
