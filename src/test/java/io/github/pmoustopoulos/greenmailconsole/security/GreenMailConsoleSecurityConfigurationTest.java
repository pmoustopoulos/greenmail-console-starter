package io.github.pmoustopoulos.greenmailconsole.security;

import io.github.pmoustopoulos.greenmailconsole.GreenMailConsoleAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                GreenMailConsoleAutoConfiguration.class,
                GreenMailConsoleSecurityConfiguration.class,
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class,
                ServletWebSecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                DispatcherServletAutoConfiguration.class,
                WebMvcAutoConfiguration.class,
                JacksonAutoConfiguration.class,
                GreenMailConsoleSecurityConfigurationTest.AppSecurityConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "greenmail.console.enabled=true",
                "greenmail.console.smtp-port=0"
        })
@AutoConfigureMockMvc
class GreenMailConsoleSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void consoleApiIsReachableWithoutAuthentication() throws Exception {
        // Default Spring Security would return 401; our permit chain must allow this through.
        mockMvc.perform(get("/mail-console/api/messages"))
                .andExpect(status().isOk());
    }

    @Test
    void nonConsolePathsAreNotOpenedByTheConsolesPermitChain() throws Exception {
        // The console's chain is scoped to /mail-console/** only. Registering it causes Boot's
        // default security to back off, so this test simulates an app that defines its own
        // catch-all chain (see AppSecurityConfiguration below) and proves that chain still
        // protects everything outside the console path.
        mockMvc.perform(get("/some-secured-path"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Stands in for an application's own security configuration. Not scoped with
     * securityMatcher, so it applies to every request not claimed by a higher-precedence,
     * more specific chain (i.e. everything outside the console's path).
     */
    @TestConfiguration
    static class AppSecurityConfiguration {

        @Bean
        SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
            return http.build();
        }
    }
}
