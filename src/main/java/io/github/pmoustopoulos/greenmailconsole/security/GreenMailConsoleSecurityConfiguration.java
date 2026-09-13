package io.github.pmoustopoulos.greenmailconsole.security;

import io.github.pmoustopoulos.greenmailconsole.GreenMailConsoleProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration
@ConditionalOnClass({SecurityFilterChain.class, EnableWebSecurity.class})
@EnableConfigurationProperties(GreenMailConsoleProperties.class)
@ConditionalOnProperty(prefix = "greenmail.console", name = "enabled", havingValue = "true")
public class GreenMailConsoleSecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain greenMailConsoleSecurityFilterChain(
            HttpSecurity http, GreenMailConsoleProperties properties) throws Exception {
        String pattern = properties.getPath() + "/**";
        http.securityMatcher(pattern)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
