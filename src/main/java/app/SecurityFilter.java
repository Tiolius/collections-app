package app;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Заголовки безопасности для всех ответов и защита от подделки запросов:
 * изменения (POST) принимаются только со страниц самого приложения.
 */
@Component
public class SecurityFilter extends OncePerRequestFilter {

    // Скрипты и стили только свои (небольшие встроенные — inline), без чужих сайтов и встраивания в чужие страницы
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    // Картинка коллекции: никаких скриптов, даже если открыть её отдельно
    private static final String CSP_IMAGE = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "same-origin");
        res.setHeader("Content-Security-Policy", req.getRequestURI().matches("/img/[0-9a-f]{64}(/thumb)?") ? CSP_IMAGE : CSP);
        if (req.isSecure()) res.setHeader("Strict-Transport-Security", "max-age=31536000");
        if ("POST".equals(req.getMethod()) && !sameOrigin(req)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(req, res);
    }

    /** Запрос отправлен со страницы этого же сайта (по заголовкам браузера Sec-Fetch-Site или Origin). */
    private static boolean sameOrigin(HttpServletRequest req) {
        String site = req.getHeader("Sec-Fetch-Site");
        if (site != null) return site.equals("same-origin") || site.equals("none");
        String origin = req.getHeader("Origin");
        if (origin == null) return true; // не браузер или очень старый браузер
        try {
            URI u = URI.create(origin);
            String host = u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
            return host.equalsIgnoreCase(req.getHeader("Host"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
