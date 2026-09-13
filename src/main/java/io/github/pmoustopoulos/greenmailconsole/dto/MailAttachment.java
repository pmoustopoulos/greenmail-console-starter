package io.github.pmoustopoulos.greenmailconsole.dto;

/**
 * Metadata for one attachment or inline part of a message.
 *
 * @param id        positional index of the part within the message (used to fetch its bytes)
 * @param filename  display name (falls back to the content id or a generated name)
 * @param contentType base MIME type, e.g. {@code image/png} or {@code application/pdf}
 * @param size      decoded size in bytes
 * @param inline    {@code true} for inline parts (e.g. images referenced via {@code cid:})
 * @param contentId the Content-ID without angle brackets (e.g. {@code logo}), or {@code null}
 */
public record MailAttachment(
        String id,
        String filename,
        String contentType,
        long size,
        boolean inline,
        String contentId) {
}
