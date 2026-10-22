package app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Без входа доступны только страница входа (и создание первого администратора); остальное — переход на /login. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                var session = req.getSession(false);
                if (session != null && session.getAttribute("uid") != null) return true;
                res.sendRedirect("/login");
                return false;
            }
        }).excludePathPatterns("/login", "/setup", "/error", "/fonts/**");
    }
}
