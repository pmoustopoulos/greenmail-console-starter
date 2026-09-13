package io.github.pmoustopoulos.greenmailconsole.dto;

/** Raw bytes of a single attachment/inline part, for download or inline rendering. */
public record MailAttachmentContent(
        byte[] data,
        String contentType,
        String filename,
        boolean inline) {
}
