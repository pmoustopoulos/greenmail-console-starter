package io.github.pmoustopoulos.greenmailconsole.hostile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Filter mode (default) inside the hostile host, no context path. */
@SpringBootTest(
        classes = HostileHostApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "greenmail.console.enabled=true",
                "greenmail.console.smtp-port=0"
        })
class FilterModeHostileHostTest extends AbstractHostileHostTest {

    @Autowired
    FilterRegistrationBean<?> mailConsoleFilterRegistration;

    @Override
    String consoleUrl() {
        return hostUrl("/mail-console");
    }

    @Override
    String expectedBasePath() {
        return "/mail-console";
    }

    @Override
    void assertNonLoopbackClientIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mail-console/api/messages");
        request.setContextPath("");
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        mailConsoleFilterRegistration.getFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("request must not reach the host").isNull();
    }
}
