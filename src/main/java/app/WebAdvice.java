package app;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Общее для всех страниц: вошедший пользователь (currentUser) и страница ошибки error.html.
 * На странице ошибки текст показывается только наш (например, «Название обязательно»); остальные ошибки
 * идут через стандартный /error без технических подробностей (server.error.include-message=never).
 */
@ControllerAdvice
public class WebAdvice {

    private final Repo repo;
    private final Updates updates;

    public WebAdvice(Repo repo, Updates updates) {
        this.repo = repo;
        this.updates = updates;
    }

    /** Новая версия приложения (или null) — администратору, для ссылки в шапке. */
    @ModelAttribute("update")
    public Updates.Release update(HttpSession session) {
        Repo.User u = currentUser(session);
        return u != null && u.isAdmin() ? updates.available() : null;
    }

    /** Вошедший пользователь (или null) — для шапки страниц и прав администратора в шаблонах. */
    /** Значения статуса прохождения для выпадающих списков. */
    @ModelAttribute("statuses")
    public java.util.List<String> statuses() {
        return Repo.STATUSES;
    }

    @ModelAttribute("currentUser")
    public Repo.User currentUser(HttpSession session) {
        return session.getAttribute("uid") instanceof Long uid ? repo.user(uid) : null;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView status(ResponseStatusException e, HttpServletResponse res, HttpSession session) {
        int status = e.getStatusCode().value();
        ModelAndView mv = error(status, res, session);
        if (e.getReason() != null && status < 500) mv.addObject("message", e.getReason());
        return mv;
    }

    /** Файл больше лимита (spring.servlet.multipart.max-file-size). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView tooLarge(HttpServletResponse res, HttpSession session) {
        return error(413, res, session);
    }

    private ModelAndView error(int status, HttpServletResponse res, HttpSession session) {
        res.setStatus(status);
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("status", status);
        mv.addObject("currentUser", currentUser(session));
        return mv;
    }
}
