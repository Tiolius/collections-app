package app;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ошибки для пользователя: ответ с кодом и нашим текстом (ключ из messages*.properties) — его показывает страница
 * ошибки (WebAdvice). Пример: {@code throw Fail.badRequest("item.badStatus");}
 */
final class Fail {

    private Fail() {}

    /** 400 без текста. */
    static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    /** 400 с текстом по ключу. */
    static ResponseStatusException badRequest(String key, Object... args) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t(key, args));
    }

    /** 404: нет такого или чужое. */
    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** 403 с текстом по ключу. */
    static ResponseStatusException forbidden(String key) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, I18n.t(key));
    }

    /** 409 (занято другим действием) с текстом по ключу. */
    static ResponseStatusException conflict(String key) {
        return new ResponseStatusException(HttpStatus.CONFLICT, I18n.t(key));
    }
}
