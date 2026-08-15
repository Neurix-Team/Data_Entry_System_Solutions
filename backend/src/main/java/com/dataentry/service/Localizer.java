package com.dataentry.service;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;
@Component
public class Localizer {

    public TranslationService.Lang currentLang() {
        Locale l = LocaleContextHolder.getLocale();
        if (l != null && "ar".equalsIgnoreCase(l.getLanguage())) return TranslationService.Lang.AR;
        return TranslationService.Lang.EN;
    }

 
    public String pick(String en, String ar, String legacy) {
        return pick(en, ar, legacy, currentLang());
    }

    public String pick(String en, String ar, String legacy, TranslationService.Lang lang) {
        if (lang == TranslationService.Lang.AR) {
            if (nonBlank(ar)) return ar;
            if (nonBlank(en)) return en;
            return legacy;
        }
        if (nonBlank(en)) return en;
        if (nonBlank(ar)) return ar;
        return legacy;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
