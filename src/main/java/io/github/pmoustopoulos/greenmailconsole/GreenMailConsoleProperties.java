package io.github.pmoustopoulos.greenmailconsole;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "greenmail.console")
public class GreenMailConsoleProperties {

    /** Where captured mail is kept. */
    public enum Storage {
        /** In the JVM heap (GreenMail's own store) — fast and cleared on restart (default). */
        MEMORY,
        /** Mirrored to {@code .eml} files on disk (see {@link #directory}) — mail survives restarts. */
        FILE
    }

    /** How the console UI and API are served. */
    public enum Mode {
        /**
         * A servlet filter on the host app's own port and URL, running ahead of the host's security,
         * filters and Spring MVC so none of them can interfere (default).
         */
        FILTER,
        /** A small library-owned HTTP server on its own port ({@link #port}), fully isolated from the host. */
        STANDALONE
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

    /**
     * How the console is served: {@code filter} (default; same port and URL as the app, ahead of the
     * app's security and MVC pipeline) or {@code standalone} (separate embedded HTTP server on
     * {@code greenmail.console.port}).
     */
    private Mode mode = Mode.FILTER;

    /**
     * Whether clients other than loopback (localhost) may reach the console. Off by default, so
     * requests from any other address get 403.
     */
    private boolean allowRemote = false;

    /**
     * Port of the standalone console server when {@code mode=standalone}. If busy, a free port is
     * used instead and logged. Ignored in {@code filter} mode.
     */
    private int port = 8025;

    /** Address the standalone console server binds to when {@code mode=standalone}. Ignored in {@code filter} mode. */
    private String bindAddress = "127.0.0.1";

    /**
     * Profiles in which the console must never run. If the console is enabled while any of these
     * profiles is active, application startup fails.
     */
    private List<String> forbiddenProfiles = new ArrayList<>(List.of("prod", "production"));

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

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isAllowRemote() {
        return allowRemote;
    }

    public void setAllowRemote(boolean allowRemote) {
        this.allowRemote = allowRemote;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public List<String> getForbiddenProfiles() {
        return forbiddenProfiles;
    }

    public void setForbiddenProfiles(List<String> forbiddenProfiles) {
        this.forbiddenProfiles = forbiddenProfiles;
    }
}
