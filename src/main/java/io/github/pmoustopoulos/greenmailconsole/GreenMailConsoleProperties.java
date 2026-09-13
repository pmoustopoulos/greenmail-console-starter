package io.github.pmoustopoulos.greenmailconsole;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "greenmail.console")
public class GreenMailConsoleProperties {

    /** Where captured mail is kept. */
    public enum Storage {
        /** In the JVM heap (GreenMail's own store) — fast and cleared on restart (default). */
        MEMORY,
        /** Mirrored to {@code .eml} files on disk (see {@link #directory}) — mail survives restarts. */
        FILE
    }

    /** Whether the in-memory mail server and console are active. Off by default. */
    private boolean enabled = false;

    /** SMTP port the embedded GreenMail server listens on. */
    private int smtpPort = 3025;

    /** Base URL path where the console UI and its API are served. */
    private String path = "/mail-console";

    /**
     * Backing store for captured mail: {@code memory} (in-heap, cleared on restart) or {@code file}
     * (mirrored to {@code .eml} files on disk that persist across restarts — like H2's file mode).
     */
    private Storage storage = Storage.MEMORY;

    /**
     * Directory used when {@link #storage} is {@code file}. Created if missing; captured messages are
     * written here as {@code .eml} files and reloaded on startup. Ignored for {@code memory} storage.
     */
    private String directory = "mail-data";

    /**
     * How often the background task flushes newly received mail to disk when {@link #storage} is
     * {@code file}. Set to {@code 0s} to disable the periodic flush. Ignored for {@code memory} storage.
     */
    private Duration persistInterval = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(int smtpPort) {
        this.smtpPort = smtpPort;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public Duration getPersistInterval() {
        return persistInterval;
    }

    public void setPersistInterval(Duration persistInterval) {
        this.persistInterval = persistInterval;
    }
}
