package io.github.pmoustopoulos.greenmailconsole;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * On-disk mirror of the captured mail used by {@code storage=file}. Each console message (one row,
 * after de-duplication) is stored as a single {@code .eml} file named
 * {@code <epochMillis>-<keyHash>.eml}, where:
 *
 * <ul>
 *   <li>{@code epochMillis} is the arrival time — it restores the original timestamp on reload and
 *       gives a natural chronological sort;</li>
 *   <li>{@code keyHash} is a short, stable hash of the message's de-duplication key — it lets the
 *       capture task skip messages already on disk and lets a delete find the right file.</li>
 * </ul>
 *
 * All operations are best-effort: I/O failures on a single message are logged and skipped rather
 * than propagated, so persistence never takes down the host application.
 */
public class FileMailStore {

    private static final Logger log = LoggerFactory.getLogger(FileMailStore.class);
    private static final String SUFFIX = ".eml";
    private static final Session SESSION = Session.getInstance(new Properties());

    private final Path directory;

    public FileMailStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    /** The directory where {@code .eml} files are kept. */
    public Path getDirectory() {
        return directory;
    }

    /** Whether a message with this de-duplication key is already stored on disk. */
    public boolean existsForKey(String dedupeKey) {
        String hash = hash(dedupeKey);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*-" + hash + SUFFIX)) {
            return stream.iterator().hasNext();
        } catch (IOException ex) {
            log.warn("Could not scan mail directory {}: {}", directory, ex.getMessage());
            return false;
        }
    }

    /**
     * Writes a message to disk if no file for its de-duplication key exists yet. The write is
     * atomic (temp file then move) so a concurrent reader never sees a half-written {@code .eml}.
     */
    public void persist(MimeMessage message, Instant arrival, String dedupeKey) {
        if (existsForKey(dedupeKey)) {
            return;
        }
        String hash = hash(dedupeKey);
        Path target = directory.resolve(arrival.toEpochMilli() + "-" + hash + SUFFIX);
        Path tmp = directory.resolve(target.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                message.writeTo(out);
            }
            move(tmp, target);
        } catch (Exception ex) {
            log.warn("Failed to persist mail {} to {}: {}", hash, directory, ex.getMessage());
            deleteQuietly(tmp);
        }
    }

    /** Reads every stored message, parsed and paired with its arrival time, sorted oldest first. */
    public List<PersistedMail> readAll() {
        List<PersistedMail> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : stream) {
                parse(file).ifPresent(result::add);
            }
        } catch (IOException ex) {
            log.warn("Could not read mail directory {}: {}", directory, ex.getMessage());
        }
        result.sort(Comparator.comparing(PersistedMail::arrival));
        return result;
    }

    /** Permanently removes the stored file(s) for a de-duplication key, if any. */
    public void deleteByKey(String dedupeKey) {
        String hash = hash(dedupeKey);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*-" + hash + SUFFIX)) {
            for (Path file : stream) {
                deleteQuietly(file);
            }
        } catch (IOException ex) {
            log.warn("Failed to delete mail {} from {}: {}", hash, directory, ex.getMessage());
        }
    }

    /** Removes every stored {@code .eml} file. */
    public void clear() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : stream) {
                deleteQuietly(file);
            }
        } catch (IOException ex) {
            log.warn("Failed to clear mail directory {}: {}", directory, ex.getMessage());
        }
    }

    private java.util.Optional<PersistedMail> parse(Path file) {
        String name = file.getFileName().toString();
        int dash = name.indexOf('-');
        if (dash <= 0) {
            log.warn("Skipping unrecognised mail file (no arrival prefix): {}", name);
            return java.util.Optional.empty();
        }
        Instant arrival;
        try {
            arrival = Instant.ofEpochMilli(Long.parseLong(name.substring(0, dash)));
        } catch (NumberFormatException ex) {
            log.warn("Skipping mail file with unparseable arrival prefix: {}", name);
            return java.util.Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return java.util.Optional.of(new PersistedMail(new MimeMessage(SESSION, in), arrival));
        } catch (Exception ex) {
            log.warn("Skipping unreadable mail file {}: {}", name, ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    private void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("Could not delete {}: {}", file, ex.getMessage());
        }
    }

    /** Short, stable, filename-safe hash of a de-duplication key. */
    private static String hash(String dedupeKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(dedupeKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is guaranteed present on every JVM; fall back defensively.
            return Integer.toHexString(dedupeKey.hashCode());
        }
    }

    /** A parsed message paired with the arrival time recovered from its filename. */
    public record PersistedMail(MimeMessage message, Instant arrival) {
    }
}
