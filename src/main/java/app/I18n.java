package app;

import java.util.Locale;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Interface language: Russian (default) or English. Chosen with the flag in the header, kept in the browser cookie "lang"
 * (set by a script in base.html). Texts — messages.properties (Russian) and messages_en.properties (English);
 * in templates — #{key}, in Java — I18n.t("key", …).
 */
@Component("localeResolver") // DispatcherServlet takes the locale resolver from the bean with this name
public class I18n implements LocaleResolver {

    public static final Locale RU = Locale.forLanguageTag("ru");
    public static final String COOKIE = "lang";

    private static MessageSource messages;

    public I18n(MessageSource messages) {
        I18n.messages = messages;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest req) {
        if (req.getCookies() != null)
            for (Cookie c : req.getCookies())
                if (COOKIE.equals(c.getName()) && "en".equals(c.getValue())) return Locale.ENGLISH;
        return RU;
    }

    @Override
    public void setLocale(HttpServletRequest req, HttpServletResponse res, Locale locale) {
        throw new UnsupportedOperationException("язык выбирается cookie «lang»");
    }

    /** Text in the language of the current request. */
    public static String t(String key, Object... args) {
        return t(LocaleContextHolder.getLocale(), key, args);
    }

    /** Text in the given language (background jobs remember the language when started). */
    public static String t(Locale locale, String key, Object... args) {
        return messages.getMessage(key, args.length == 0 ? null : args, locale);
    }
}
