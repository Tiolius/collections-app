package app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Without signing in only the login page (and creating the first administrator) is available; anything else goes to /login.
 * A session is valid while the user's password is unchanged: a password change ends their other sessions.
 * No session, but the computer was remembered at sign-in (Remember) — signed in without a password.
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
                    session.invalidate(); // user deleted or password changed in another session
                }
                // Computer remembered at sign-in ("Remember me") — a new session without entering the password
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
