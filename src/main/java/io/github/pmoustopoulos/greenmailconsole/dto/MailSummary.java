package io.github.pmoustopoulos.greenmailconsole.dto;

public record MailSummary(
        String id,
        String from,
        String to,
        String subject,
        String preview,
        String receivedAt,
        boolean read) {
}
