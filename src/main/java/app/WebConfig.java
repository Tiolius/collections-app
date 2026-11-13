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
 * Нет сессии, но компьютер запомнен при входе (Remember) — вход без пароля.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Repo repo;
    private final Remember remember;

    public WebConfig(Repo repo, Remember remember) {
        this.repo = repo;
        this.remember = remember;
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
                // Компьютер запомнен при входе («Запомнить меня») — новая сессия без ввода пароля
                Long uid = remember.user(req);
                Repo.User u = uid == null ? null : repo.user(uid);
                if (u != null) {
                    var s = req.getSession(true);
                    s.setAttribute("uid", u.id());
                    s.setAttribute(WebController.SESSION_PW, u.passwordHash());
                    return true;
                }
                res.sendRedirect("/login");
                return false;
            }
        }).excludePathPatterns("/login", "/setup", "/error", "/favicon.svg", "/app.css", "/fonts/**");
    }
}
