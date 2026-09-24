package io.github.pmoustopoulos.greenmailconsole;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MailConsoleFilterTest {

    private GreenMail greenMail;
    private MailConsoleFilter filter;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        filter = new MailConsoleFilter(
                new MailConsoleHandler(new MailConsoleService(greenMail), false), "/mail-console");
    }

    @AfterEach
    void tearDown() {
        greenMail.stop();
    }

    @Test
    void handlesConsolePathWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mail-console");
        request.setContextPath("/api");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(response.getContentAsString()).contains("const BASE = '/api/mail-console'");
        assertThat(chain.getRequest()).as("host chain must not run").isNull();
    }

    @Test
    void delegatesNonConsolePaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mail-console-other");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void rejectsNonLoopbackRemoteAddress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mail-console/api/messages");
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }
}
