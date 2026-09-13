package io.github.pmoustopoulos.greenmailconsole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                GreenMailConsoleAutoConfiguration.class,
                MailSenderAutoConfiguration.class,
                DispatcherServletAutoConfiguration.class,
                WebMvcAutoConfiguration.class,
                JacksonAutoConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "greenmail.console.enabled=true",
                "greenmail.console.smtp-port=3125",
                "spring.mail.host=localhost",
                "spring.mail.port=3125"
        })
@AutoConfigureMockMvc
class MailConsoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JavaMailSender mailSender;

    private void sendMail() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("from@localhost");
        message.setTo("to@localhost");
        message.setSubject("Integration subject");
        message.setText("Integration body");
        mailSender.send(message);
    }

    @Test
    void listViewAndClearMessages() throws Exception {
        sendMail();

        mockMvc.perform(get("/mail-console/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].subject").value("Integration subject"));

        mockMvc.perform(get("/mail-console/api/messages/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text", containsString("Integration body")));

        mockMvc.perform(get("/mail-console/api/messages/999"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/mail-console/api/messages"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/mail-console/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void servesAttachmentBytesForDownload() throws Exception {
        // Isolate from other methods that share this context's GreenMail instance
        mockMvc.perform(delete("/mail-console/api/messages")).andExpect(status().isNoContent());

        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom("from@localhost");
        helper.setTo("to@localhost");
        helper.setSubject("Attachment test");
        helper.setText("plain body", false);
        helper.addAttachment("notes.txt",
                new ByteArrayResource("hello world".getBytes(StandardCharsets.UTF_8)), "text/plain");
        mailSender.send(mime);

        mockMvc.perform(get("/mail-console/api/messages/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments", hasSize(1)))
                .andExpect(jsonPath("$.attachments[0].filename").value("notes.txt"))
                .andExpect(jsonPath("$.attachments[0].inline").value(false));

        mockMvc.perform(get("/mail-console/api/messages/0/parts/0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes("hello world".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(delete("/mail-console/api/messages")).andExpect(status().isNoContent());
    }

    @Test
    void deletesSingleMessage() throws Exception {
        mockMvc.perform(delete("/mail-console/api/messages")).andExpect(status().isNoContent()); // clean slate

        sendMail();
        sendMail();
        mockMvc.perform(get("/mail-console/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // delete the first message; the other remains
        mockMvc.perform(delete("/mail-console/api/messages/0"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/mail-console/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // deleting an unknown id yields 404
        mockMvc.perform(delete("/mail-console/api/messages/999"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/mail-console/api/messages")).andExpect(status().isNoContent()); // cleanup
    }

    @Test
    void servesConsoleHtmlWithInjectedBasePath() throws Exception {
        mockMvc.perform(get("/mail-console"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("GreenMail Console")))
                .andExpect(content().string(containsString("const BASE = '/mail-console'")));
    }

    @Test
    void injectsServletContextPathIntoBasePath() throws Exception {
        // When the host app runs under a context path, the console's JS BASE must include it
        // so its API calls resolve correctly (e.g. customer-api runs under /customer-api).
        mockMvc.perform(get("/customer-api/mail-console").contextPath("/customer-api"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("const BASE = '/customer-api/mail-console'")));
    }
}
