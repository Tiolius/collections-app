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
 * Язык интерфейса: русский (по умолчанию) или английский. Выбор — флажок в шапке, хранится в cookie «lang» браузера
 * (ставит скрипт в base.html). Тексты — messages.properties (русский) и messages_en.properties (английский);
 * в шаблонах — #{ключ}, в Java — I18n.t("ключ", …).
 */
@Component("localeResolver") // DispatcherServlet берёт определение языка из бина с этим именем
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

    /** Текст на языке текущего запроса. */
    public static String t(String key, Object... args) {
        return t(LocaleContextHolder.getLocale(), key, args);
    }

    /** Текст на заданном языке (фоновые задачи запоминают язык при запуске). */
    public static String t(Locale locale, String key, Object... args) {
        return messages.getMessage(key, args.length == 0 ? null : args, locale);
    }
}
