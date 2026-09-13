package io.github.pmoustopoulos.greenmailconsole.dto;

import java.util.List;
import java.util.Map;

public record MailDetail(
        String id,
        String from,
        String to,
        String cc,
        String subject,
        String receivedAt,
        Map<String, String> headers,
        String text,
        String html,
        String source,
        long size,
        List<MailAttachment> attachments) {
}
