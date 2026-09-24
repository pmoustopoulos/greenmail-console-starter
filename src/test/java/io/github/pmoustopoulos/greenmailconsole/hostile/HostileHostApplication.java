package io.github.pmoustopoulos.greenmailconsole.hostile;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * A deliberately hostile host application: everything a real app can register that would break a
 * console served through the host's MVC/filter pipeline. The console must work inside it with no
 * host changes beyond {@code greenmail.console.enabled=true}, and must not weaken its security.
 */
@SpringBootApplication
public class HostileHostApplication {

    static final String ENTRY_POINT_HEADER = "X-Host-Entry-Point";
    static final String REQUIRED_HEADER = "X-Hostile-Required";

    /** (1) A global interceptor that throws when there is no Referer (the real-world failure). */
    @Bean
    WebMvcConfigurer refererInterceptorConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if (request.getHeader("Referer") == null) {
                            throw new IllegalStateException("Referer header not found");
                        }
                        return true;
                    }
                }).addPathPatterns("/**");
            }
        };
    }

    /** (2) Exception translation to a 400 error JSON. */
    @RestControllerAdvice
    static class HostileExceptionAdvice {
        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> handle(Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(ex.getMessage())));
        }
    }

    /** (2) Wraps every (non-String) response body in {"wrapped": ...}. */
    @RestControllerAdvice
    static class HostileWrappingAdvice implements ResponseBodyAdvice<Object> {
        @Override
        public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
            return !StringHttpMessageConverter.class.isAssignableFrom(converterType);
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                      Class<? extends HttpMessageConverter<?>> converterType,
                                      ServerHttpRequest request, ServerHttpResponse response) {
            return Collections.singletonMap("wrapped", body);
        }
    }

    /** (3) Everything requires authentication, with a custom 401 entry point. */
    @Bean
    SecurityFilterChain hostSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setHeader(ENTRY_POINT_HEADER, "true");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                }));
        return http.build();
    }

    /** (3) A JWT-style filter declared as a component, so Boot also registers it as a plain servlet filter. */
    @Component
    static class AuthorizationHeaderFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getHeader("Authorization") == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing Authorization");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /** (4) A customised JSON mapper: snake_case and root wrapping. */
    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.WRAP_ROOT_VALUE)
                .build();
    }

    /** (5) A servlet filter that rejects requests missing a custom header. */
    @Bean
    Filter requiredHeaderFilter() {
        return (request, response, chain) -> {
            if (((HttpServletRequest) request).getHeader(REQUIRED_HEADER) == null) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_BAD_REQUEST, "missing " + REQUIRED_HEADER);
                return;
            }
            chain.doFilter(request, response);
        };
    }

    @RestController
    static class HostController {
        @GetMapping("/some-host-endpoint")
        Map<String, String> secret() {
            return Map.of("secret", "host data");
        }
    }
}
