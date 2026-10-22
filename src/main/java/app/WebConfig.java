package app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Без входа доступны только страница входа (и создание первого администратора); остальное — переход на /login.
 * Сессия действует, пока пароль пользователя не менялся: смена пароля завершает его остальные сессии.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Repo repo;

    public WebConfig(Repo repo) {
        this.repo = repo;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                var session = req.getSession(false);
                if (session != null && session.getAttribute("uid") instanceof Long uid) {
                    Repo.User u = repo.user(uid);
                    if (u != null && u.passwordHash().equals(session.getAttribute(WebController.SESSION_PW))) return true;
                    session.invalidate(); // пользователь удалён или пароль сменён в другой сессии
                }
                res.sendRedirect("/login");
                return false;
            }
        }).excludePathPatterns("/login", "/setup", "/error", "/favicon.svg");
    }
}
