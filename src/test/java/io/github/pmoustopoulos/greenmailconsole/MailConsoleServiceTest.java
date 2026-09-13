package io.github.pmoustopoulos.greenmailconsole;

import io.github.pmoustopoulos.greenmailconsole.dto.MailAttachment;
import io.github.pmoustopoulos.greenmailconsole.dto.MailAttachmentContent;
import io.github.pmoustopoulos.greenmailconsole.dto.MailDetail;
import io.github.pmoustopoulos.greenmailconsole.dto.MailSummary;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MailConsoleServiceTest {

    private GreenMail greenMail;
    private MailConsoleService service;

    @BeforeEach
    void setUp() {
        // Dynamic port avoids clashes when tests run in parallel / on Windows reserved ranges
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        service = new MailConsoleService(greenMail);
    }

    @AfterEach
    void tearDown() {
        greenMail.stop();
    }

    @Test
    void listsAndReadsAReceivedMessage() {
        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "Hello subject", "Hello body", greenMail.getSmtp().getServerSetup());

        List<MailSummary> summaries = service.listMessages();
        assertThat(summaries).hasSize(1);
        MailSummary summary = summaries.get(0);
        assertThat(summary.subject()).isEqualTo("Hello subject");
        assertThat(summary.from()).contains("from@localhost");
        assertThat(summary.to()).contains("to@localhost");

        Optional<MailDetail> detail = service.getMessage(summary.id());
        assertThat(detail).isPresent();
        assertThat(detail.get().text()).contains("Hello body");
        assertThat(detail.get().headers()).containsKey("Subject");
        // Raw source (for the Source tab) includes the headers and body
        assertThat(detail.get().source()).contains("Subject: Hello subject");
        assertThat(detail.get().source()).contains("Hello body");
        // Size is the raw message length in bytes
        assertThat(detail.get().size()).isGreaterThan(0);
    }

    @Test
    void readsMultipartMessageWithTextAndHtmlParts() throws Exception {
        ServerSetup serverSetup = greenMail.getSmtp().getServerSetup();
        Session session = GreenMailUtil.getSession(serverSetup);

        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.setFrom(new InternetAddress("from@localhost"));
        mimeMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse("to@localhost"));
        mimeMessage.setSubject("Multipart subject");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Plain text body", "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>HTML body</p>", "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(htmlPart);
        mimeMessage.setContent(multipart);
        mimeMessage.saveChanges();

        GreenMailUtil.sendMimeMessage(mimeMessage);

        List<MailSummary> summaries = service.listMessages();
        assertThat(summaries).hasSize(1);

        Optional<MailDetail> detail = service.getMessage(summaries.get(0).id());
        assertThat(detail).isPresent();
        assertThat(detail.get().text()).contains("Plain text body");
        assertThat(detail.get().html()).contains("<p>HTML body</p>");
    }

    @Test
    void tracksReadStateArrivalTimeAndPreview() {
        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "Subject line", "This is the body preview text", greenMail.getSmtp().getServerSetup());

        MailSummary before = service.listMessages().get(0);
        assertThat(before.read()).isFalse();
        assertThat(before.receivedAt()).isNotBlank();
        assertThat(before.preview()).contains("This is the body preview text");

        // Opening the message marks it read
        service.getMessage(before.id());

        MailSummary after = service.listMessages().get(0);
        assertThat(after.read()).isTrue();
        // Arrival time is stable across list calls (recorded once, on first observation)
        assertThat(after.receivedAt()).isEqualTo(before.receivedAt());
    }

    @Test
    void extractsAttachmentsAndInlineImagesAndServesContent() throws Exception {
        ServerSetup serverSetup = greenMail.getSmtp().getServerSetup();
        Session session = GreenMailUtil.getSession(serverSetup);

        MimeMessage mimeMessage = new MimeMessage(session);
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom("from@localhost");
        helper.setTo("to@localhost");
        helper.setSubject("With parts");
        helper.setText("<p>Logo: <img src=\"cid:logo\"></p>", true);
        helper.addInline("logo", new ByteArrayResource("PNGDATA".getBytes(StandardCharsets.UTF_8)), "image/png");
        helper.addAttachment("report.txt",
                new ByteArrayResource("hello world".getBytes(StandardCharsets.UTF_8)), "text/plain");

        GreenMailUtil.sendMimeMessage(mimeMessage);

        MailDetail detail = service.getMessage(service.listMessages().get(0).id()).orElseThrow();
        assertThat(detail.attachments()).hasSize(2);

        MailAttachment inline = detail.attachments().stream()
                .filter(MailAttachment::inline).findFirst().orElseThrow();
        assertThat(inline.contentId()).isEqualTo("logo");
        assertThat(inline.contentType()).isEqualTo("image/png");

        MailAttachment file = detail.attachments().stream()
                .filter(a -> !a.inline()).findFirst().orElseThrow();
        assertThat(file.filename()).isEqualTo("report.txt");
        assertThat(file.contentType()).isEqualTo("text/plain");
        assertThat(file.size()).isGreaterThan(0);

        Optional<MailAttachmentContent> content = service.getAttachmentContent(detail.id(), file.id());
        assertThat(content).isPresent();
        assertThat(new String(content.get().data(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        assertThat(content.get().inline()).isFalse();

        // Unknown part id yields empty
        assertThat(service.getAttachmentContent(detail.id(), "999")).isEmpty();
    }

    @Test
    void deduplicatesMessagesDeliveredToMultipleRecipients() throws Exception {
        ServerSetup serverSetup = greenMail.getSmtp().getServerSetup();
        Session session = GreenMailUtil.getSession(serverSetup);

        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.setFrom(new InternetAddress("from@localhost"));
        mimeMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse("a@localhost,b@localhost"));
        mimeMessage.setRecipients(Message.RecipientType.CC, InternetAddress.parse("c@localhost"));
        mimeMessage.setSubject("Broadcast");
        mimeMessage.setText("hello all");
        mimeMessage.saveChanges(); // generates the Message-ID used for de-duplication

        GreenMailUtil.sendMimeMessage(mimeMessage);

        // GreenMail delivers one copy per recipient (3), but the console shows a single entry
        assertThat(greenMail.getReceivedMessages().length).isEqualTo(3);

        List<MailSummary> summaries = service.listMessages();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).subject()).isEqualTo("Broadcast");

        // The representative entry is still fully readable
        assertThat(service.getMessage(summaries.get(0).id())).isPresent();
    }

    @Test
    void previewStripsStyleScriptAndCommentsFromHtmlBody() throws Exception {
        ServerSetup serverSetup = greenMail.getSmtp().getServerSetup();
        Session session = GreenMailUtil.getSession(serverSetup);

        String html = "<html><head>"
                + "<title>Email Template</title>"
                + "<style>/* Define your CSS styles here */ body { font-family: Arial, sans-serif; }</style>"
                + "</head>"
                + "<!-- Email Template comment -->"
                + "<body><h1>Welcome</h1><p>Your account is ready.</p></body></html>";

        MimeMessage mimeMessage = new MimeMessage(session);
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom("from@localhost");
        helper.setTo("to@localhost");
        helper.setSubject("Test Email");
        helper.setText(html, true);
        GreenMailUtil.sendMimeMessage(mimeMessage);

        MailSummary summary = service.listMessages().get(0);
        assertThat(summary.preview()).contains("Welcome");
        assertThat(summary.preview()).contains("Your account is ready.");
        assertThat(summary.preview()).doesNotContain("font-family");
        assertThat(summary.preview()).doesNotContain("Define your CSS");
    }

    @Test
    void exposesCcAndDeletesIndividualMessage() throws Exception {
        ServerSetup serverSetup = greenMail.getSmtp().getServerSetup();
        Session session = GreenMailUtil.getSession(serverSetup);

        MimeMessage first = new MimeMessage(session);
        first.setFrom(new InternetAddress("from@localhost"));
        first.setRecipients(Message.RecipientType.TO, InternetAddress.parse("to@localhost"));
        first.setRecipients(Message.RecipientType.CC, InternetAddress.parse("cc@localhost"));
        first.setSubject("First");
        first.setText("one");
        first.saveChanges();
        GreenMailUtil.sendMimeMessage(first);

        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "Second", "two", serverSetup);

        List<MailSummary> before = service.listMessages();
        assertThat(before).hasSize(2);

        MailSummary firstSummary = before.stream()
                .filter(s -> s.subject().equals("First")).findFirst().orElseThrow();

        // Cc is exposed in the detail view
        MailDetail detail = service.getMessage(firstSummary.id()).orElseThrow();
        assertThat(detail.cc()).contains("cc@localhost");

        // Deleting one message hides it (and all its per-recipient copies), leaving the other
        assertThat(service.deleteMessage(firstSummary.id())).isTrue();
        List<MailSummary> after = service.listMessages();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).subject()).isEqualTo("Second");
        assertThat(service.getMessage(firstSummary.id())).isEmpty();

        // Deleting an unknown id returns false
        assertThat(service.deleteMessage("999")).isFalse();
    }

    @Test
    void returnsEmptyForUnknownIdAndClearsMailbox() {
        GreenMailUtil.sendTextEmail("to@localhost", "from@localhost",
                "s", "b", greenMail.getSmtp().getServerSetup());
        assertThat(service.listMessages()).hasSize(1);

        assertThat(service.getMessage("999")).isEmpty();

        service.clearMessages();
        assertThat(service.listMessages()).isEmpty();
    }
}
