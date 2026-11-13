package app;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Errors for the user: a response with a status code and our text (a key from messages*.properties), shown by the error
 * page (WebAdvice). Example: {@code throw Fail.badRequest("item.badStatus");}
 */
final class Fail {

    private Fail() {}

    /** 400 without text. */
    static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    /** 400 with text by key. */
    static ResponseStatusException badRequest(String key, Object... args) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, I18n.t(key, args));
    }

    /** 404: no such thing or someone else's. */
    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** 403 with text by key. */
    static ResponseStatusException forbidden(String key) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, I18n.t(key));
    }

    /** 409 (busy with another action) with text by key. */
    static ResponseStatusException conflict(String key) {
        return new ResponseStatusException(HttpStatus.CONFLICT, I18n.t(key));
    }
}
