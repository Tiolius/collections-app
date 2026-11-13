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
 * Common to all pages: the signed-in user (currentUser) and the error page error.html.
 * The error page shows only our own text (for example, "Name is required"); other errors
 * go through the standard /error without technical details (server.error.include-message=never).
 */
@ControllerAdvice
public class WebAdvice {

    private final Repo repo;
    private final Updates updates;

    public WebAdvice(Repo repo, Updates updates) {
        this.repo = repo;
        this.updates = updates;
    }

    /** New application version (or null) — for the administrator, for the link in the header. */
    @ModelAttribute("update")
    public Updates.Release update(HttpSession session) {
        Repo.User u = currentUser(session);
        return u != null && u.isAdmin() ? updates.available() : null;
    }

    /** Theme pair of the signed-in user (a Repo.THEMES key) or null before sign-in (then taken from the browser). */
    @ModelAttribute("themePair")
    public String themePair(HttpSession session) {
        return session.getAttribute("uid") instanceof Long uid ? repo.themePair(uid) : null;
    }

    /** Signed-in user (or null) — for the page header and administrator rights in templates. */
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

    /** File larger than the limit (spring.servlet.multipart.max-file-size). */
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
