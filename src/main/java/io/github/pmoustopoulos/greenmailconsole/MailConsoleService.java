package io.github.pmoustopoulos.greenmailconsole;

import io.github.pmoustopoulos.greenmailconsole.dto.MailAttachment;
import io.github.pmoustopoulos.greenmailconsole.dto.MailAttachmentContent;
import io.github.pmoustopoulos.greenmailconsole.dto.MailDetail;
import io.github.pmoustopoulos.greenmailconsole.dto.MailSummary;
import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.Header;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only view over GreenMail's in-memory message store, plus lightweight
 * console-only metadata (arrival time and read/unread state) that GreenMail
 * itself does not track. Messages are identified by their positional index in
 * the append-only store; attachments/inline parts are identified by their
 * positional index within a message. Both metadata maps reset on clear.
 */
public class MailConsoleService {

    private static final int PREVIEW_LENGTH = 140;

    private final GreenMail greenMail;
    // On-disk mirror when storage=file; null in memory mode (behaviour then unchanged).
    private final FileMailStore fileStore;

    // Console-only metadata keyed by the message's positional index.
    private final Map<Integer, Instant> arrivedAt = new ConcurrentHashMap<>();
    private final Set<Integer> readIndices = ConcurrentHashMap.newKeySet();
    // Soft-deleted messages, keyed by dedupe key (GreenMail has no per-message delete).
    private final Set<String> deletedKeys = ConcurrentHashMap.newKeySet();

    public MailConsoleService(GreenMail greenMail) {
        this(greenMail, null);
    }

    public MailConsoleService(GreenMail greenMail, FileMailStore fileStore) {
        this.greenMail = greenMail;
        this.fileStore = fileStore;
    }

    public List<MailSummary> listMessages() {
        MimeMessage[] messages = greenMail.getReceivedMessages();

        // GreenMail delivers one copy per recipient (To + Cc + Bcc), so a single
        // message sent to N recipients appears N times in getReceivedMessages().
        // Collapse those copies into one entry, keyed by Message-ID, keeping the
        // first (lowest-index) copy as the representative.
        Map<String, Integer> representatives = new LinkedHashMap<>();
        for (int i = 0; i < messages.length; i++) {
            String key = dedupeKey(messages[i], i);
            if (deletedKeys.contains(key)) {
                continue; // hide individually deleted messages
            }
            representatives.putIfAbsent(key, i);
        }

        List<MailSummary> result = new ArrayList<>(representatives.size());
        for (int index : representatives.values()) {
            MimeMessage message = messages[index];
            Instant arrival = arrivedAt.computeIfAbsent(index, k -> Instant.now());
            result.add(new MailSummary(
                    Integer.toString(index),
                    header(message, "From"),
                    header(message, "To"),
                    subject(message),
                    preview(message),
                    arrival.toString(),
                    readIndices.contains(index)));
        }
        return result;
    }

    /**
     * Key used to collapse per-recipient duplicate deliveries of the same message.
     * Prefers the Message-ID; falls back to stable original headers (Date/From/To/Cc/Subject)
     * for messages that carry no Message-ID, so those still de-duplicate.
     */
    static String dedupeKey(MimeMessage message, int index) {
        String messageId = header(message, "Message-ID");
        if (!messageId.isBlank()) {
            return "mid:" + messageId;
        }
        String date = header(message, "Date");
        String from = header(message, "From");
        String to = header(message, "To");
        String cc = header(message, "Cc");
        String subject = subject(message);
        String composite = date + from + to + cc + subject;
        if (composite.isBlank()) {
            return "idx-" + index; // nothing stable to key on; keep as distinct
        }
        return "sig:" + date + "|" + from + "|" + to + "|" + cc + "|" + subject;
    }

    public Optional<MailDetail> getMessage(String id) {
        MimeMessage[] messages = greenMail.getReceivedMessages();
        int index = parseIndex(id);
        if (index < 0 || index >= messages.length) {
            return Optional.empty();
        }
        MimeMessage message = messages[index];
        if (deletedKeys.contains(dedupeKey(message, index))) {
            return Optional.empty();
        }

        // Opening a message marks it as read
        readIndices.add(index);
        Instant arrival = arrivedAt.computeIfAbsent(index, k -> Instant.now());

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<Part> parts = new ArrayList<>();
        try {
            walk(message, text, html, parts);
        } catch (Exception ex) {
            text.append(safeRawContent(message));
        }

        List<MailAttachment> attachments = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            attachments.add(toAttachment(parts.get(i), i));
        }

        byte[] raw = rawBytes(message);

        return Optional.of(new MailDetail(
                id,
                header(message, "From"),
                header(message, "To"),
                header(message, "Cc"),
                subject(message),
                arrival.toString(),
                headers(message),
                text.toString(),
                html.toString(),
                new String(raw, java.nio.charset.StandardCharsets.UTF_8),
                raw.length,
                attachments));
    }

    /** The complete raw MIME bytes of the message (headers + body), for the Source tab and size. */
    private byte[] rawBytes(MimeMessage message) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            message.writeTo(out);
            return out.toByteArray();
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    /**
     * Soft-deletes a single message (GreenMail has no per-message delete API): the message and
     * all its per-recipient copies are hidden from the console. Returns {@code false} if the id
     * does not resolve to a message.
     */
    public boolean deleteMessage(String id) {
        MimeMessage[] messages = greenMail.getReceivedMessages();
        int index = parseIndex(id);
        if (index < 0 || index >= messages.length) {
            return false;
        }
        String key = dedupeKey(messages[index], index);
        deletedKeys.add(key);
        readIndices.remove(index);
        arrivedAt.remove(index);
        if (fileStore != null) {
            fileStore.deleteByKey(key); // make the delete permanent across restarts
        }
        return true;
    }

    /** Returns the raw bytes of the given attachment/inline part, for download or inline rendering. */
    public Optional<MailAttachmentContent> getAttachmentContent(String messageId, String partId) {
        MimeMessage[] messages = greenMail.getReceivedMessages();
        int msgIndex = parseIndex(messageId);
        int partIndex = parseIndex(partId);
        if (msgIndex < 0 || msgIndex >= messages.length || partIndex < 0) {
            return Optional.empty();
        }
        if (deletedKeys.contains(dedupeKey(messages[msgIndex], msgIndex))) {
            return Optional.empty();
        }
        List<Part> parts = new ArrayList<>();
        try {
            walk(messages[msgIndex], new StringBuilder(), new StringBuilder(), parts);
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (partIndex >= parts.size()) {
            return Optional.empty();
        }
        Part part = parts.get(partIndex);
        try {
            byte[] data = part.getInputStream().readAllBytes();
            MailAttachment meta = toAttachment(part, partIndex);
            return Optional.of(new MailAttachmentContent(
                    data, meta.contentType(), meta.filename(), meta.inline()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public void clearMessages() {
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to purge GreenMail mailboxes", ex);
        } finally {
            arrivedAt.clear();
            readIndices.clear();
            deletedKeys.clear();
            if (fileStore != null) {
                fileStore.clear(); // wipe the on-disk mirror too
            }
        }
    }

    /** Restores the arrival time recorded for a message on reload (used by file-mode persistence). */
    void seedArrival(int index, Instant arrival) {
        arrivedAt.put(index, arrival);
    }

    /** The arrival time already recorded for a message index, or {@code null} if none yet. */
    Instant recordedArrival(int index) {
        return arrivedAt.get(index);
    }

    /**
     * Walks the MIME tree, appending body content to {@code text}/{@code html} and collecting
     * attachment/inline leaf parts (named files or parts carrying a Content-ID) into {@code parts}.
     */
    private void walk(Part part, StringBuilder text, StringBuilder html, List<Part> parts) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                walk(multipart.getBodyPart(i), text, html, parts);
            }
            return;
        }
        // leaf part
        if (isAttachmentLike(part)) {
            parts.add(part);
            return;
        }
        Object content = part.getContent();
        if (content instanceof String body) {
            if (part.isMimeType("text/html")) {
                html.append(body);
            } else {
                text.append(body);
            }
        }
    }

    private boolean isAttachmentLike(Part part) {
        try {
            String disposition = part.getDisposition();
            return part.getFileName() != null
                    || Part.ATTACHMENT.equalsIgnoreCase(disposition)
                    || hasContentId(part);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasContentId(Part part) {
        try {
            String[] cid = part.getHeader("Content-ID");
            return cid != null && cid.length > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private MailAttachment toAttachment(Part part, int id) {
        String filename = safe(() -> part.getFileName());
        String contentId = null;
        try {
            String[] cid = part.getHeader("Content-ID");
            if (cid != null && cid.length > 0) {
                contentId = stripBrackets(cid[0]);
            }
        } catch (Exception ignored) {
            // best effort
        }
        String disposition = safe(() -> part.getDisposition());
        boolean inline = Part.INLINE.equalsIgnoreCase(disposition)
                || (contentId != null && !Part.ATTACHMENT.equalsIgnoreCase(disposition));
        String contentType = stripParams(safe(() -> part.getContentType()));
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        long size = 0;
        try {
            size = part.getInputStream().readAllBytes().length;
        } catch (Exception ignored) {
            // leave size 0 if unreadable
        }
        String name = filename != null ? filename
                : (contentId != null ? contentId : "part-" + id);
        return new MailAttachment(Integer.toString(id), name, contentType, size, inline, contentId);
    }

    /** A short, single-line plain-text snippet of the body for the inbox list. */
    private String preview(MimeMessage message) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        try {
            walk(message, text, html, new ArrayList<>());
        } catch (Exception ex) {
            text.append(safeRawContent(message));
        }
        String base = text.length() > 0 ? text.toString() : htmlToText(html.toString());
        base = base.replaceAll("\\s+", " ").trim();
        if (base.length() > PREVIEW_LENGTH) {
            return base.substring(0, PREVIEW_LENGTH).trim() + "…";
        }
        return base;
    }

    /** Extracts human-readable text from an HTML body for the list preview. */
    private String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = html;
        // Drop <script>/<style> blocks (including their CSS/JS contents)
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        // Drop HTML comments (e.g. <!-- Email Template -->)
        s = s.replaceAll("(?s)<!--.*?-->", " ");
        // Strip the remaining tags
        s = s.replaceAll("<[^>]+>", " ");
        // Decode the most common HTML entities
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s;
    }

    private Map<String, String> headers(MimeMessage message) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            Enumeration<Header> headers = message.getAllHeaders();
            while (headers.hasMoreElements()) {
                Header header = headers.nextElement();
                map.putIfAbsent(header.getName(), header.getValue());
            }
        } catch (Exception ex) {
            // best-effort headers
        }
        return map;
    }

    private static String header(MimeMessage message, String name) {
        try {
            String[] values = message.getHeader(name);
            return (values != null && values.length > 0) ? values[0] : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private static String subject(MimeMessage message) {
        try {
            String subject = message.getSubject();
            return subject != null ? subject : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private String safeRawContent(MimeMessage message) {
        try {
            Object content = message.getContent();
            return content != null ? content.toString() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String stripBrackets(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String stripParams(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private String safe(ThrowingSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            return null;
        }
    }
}
