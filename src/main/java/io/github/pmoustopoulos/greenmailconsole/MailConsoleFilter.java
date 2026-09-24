package io.github.pmoustopoulos.greenmailconsole;

import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler.ConsoleRequest;
import io.github.pmoustopoulos.greenmailconsole.MailConsoleHandler.ConsoleResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Serves the mail console on the host app's own port, <em>outside</em> the host's Spring MVC and
 * filter pipeline.
 *
 * <p>Registered at {@code Ordered.HIGHEST_PRECEDENCE} for {@code {path}} and {@code {path}/*}, so it
 * runs before Spring Security's {@code DelegatingFilterProxy} and every host filter. For console
 * paths it writes the response itself and never calls {@code chain.doFilter}, which means host
 * interceptors, {@code @ControllerAdvice}, message converters, JSON mappers, security rules and
 * filters never see the request — and the library never has to relax the host's security.
 */
public class MailConsoleFilter extends OncePerRequestFilter {

    private final MailConsoleHandler handler;
    private final String path;

    public MailConsoleFilter(MailConsoleHandler handler, String path) {
        this.handler = handler;
        this.path = MailConsoleHandler.normalizePath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI().substring(contextPath.length());
        // Only {path} itself or {path}/... belongs to the console (not e.g. {path}-other).
        if (!uri.equals(path) && !uri.startsWith(path + "/")) {
            chain.doFilter(request, response);
            return;
        }

        ConsoleResponse result = handler.handle(new ConsoleRequest(
                request.getMethod(),
                uri.substring(path.length()),
                contextPath + path,
                request.getRemoteAddr()));

        response.setStatus(result.status());
        result.headers().forEach(response::setHeader);
        if (result.body().length > 0) {
            response.setContentLength(result.body().length);
            response.getOutputStream().write(result.body());
        }
        response.flushBuffer();
    }
}
