package de.notjan.bot.message;

import de.notjan.bot.message.MessagePayload.Embed;
import de.notjan.bot.util.UserFacingException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class PayloadValidator {

    public static final int MAX_CONTENT = 2000;
    public static final int MAX_EMBEDS = 10;
    public static final int MAX_TITLE = 256;
    public static final int MAX_DESCRIPTION = 4096;
    public static final int MAX_FIELDS = 25;
    public static final int MAX_FIELD_NAME = 256;
    public static final int MAX_FIELD_VALUE = 1024;
    public static final int MAX_FOOTER = 2048;
    public static final int MAX_AUTHOR = 256;
    public static final int MAX_TOTAL = 6000;
    public static final int MAX_URL = 2048;

    private PayloadValidator() {
    }

    public static void require(MessagePayload payload) {
        List<String> errors = validate(payload);
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
    }

    public static List<String> validate(MessagePayload payload) {
        List<String> errors = new ArrayList<>();
        if (payload == null) {
            errors.add("Die Nachricht fehlt.");
            return errors;
        }
        String content = payload.content();
        if (content != null && content.length() > MAX_CONTENT) {
            errors.add("Der Nachrichtentext darf höchstens " + MAX_CONTENT + " Zeichen haben.");
        }
        if (isBlank(content) && payload.embeds().isEmpty()) {
            errors.add("Die Nachricht ist leer. Füge Text oder ein Embed hinzu.");
        }
        if (payload.embeds().size() > MAX_EMBEDS) {
            errors.add("Eine Nachricht kann höchstens " + MAX_EMBEDS + " Embeds enthalten.");
        }

        int total = 0;
        for (int i = 0; i < payload.embeds().size(); i++) {
            Embed embed = payload.embeds().get(i);
            String prefix = payload.embeds().size() > 1 ? "Embed " + (i + 1) + ": " : "";
            validateEmbed(embed, prefix, errors);
            total += textLength(embed);
        }
        if (total > MAX_TOTAL) {
            errors.add("Alle Embeds zusammen dürfen höchstens " + MAX_TOTAL + " Zeichen Text haben (aktuell " + total + ").");
        }
        return errors;
    }

    private static void validateEmbed(Embed embed, String prefix, List<String> errors) {
        if (isEmpty(embed)) {
            errors.add(prefix + "Das Embed ist leer.");
            return;
        }
        maxLength(embed.title(), MAX_TITLE, prefix + "Der Titel", errors);
        maxLength(embed.description(), MAX_DESCRIPTION, prefix + "Die Beschreibung", errors);
        url(embed.url(), prefix + "Der Titel-Link", errors);
        if (embed.color() != null && (embed.color() < 0 || embed.color() > 0xFFFFFF)) {
            errors.add(prefix + "Die Farbe ist ungültig.");
        }
        if (embed.timestamp() != null) {
            try {
                OffsetDateTime.parse(embed.timestamp());
            } catch (DateTimeParseException e) {
                errors.add(prefix + "Der Zeitstempel ist ungültig.");
            }
        }
        if (embed.author() != null) {
            if (isBlank(embed.author().name()) && (!isBlank(embed.author().url()) || !isBlank(embed.author().iconUrl()))) {
                errors.add(prefix + "Der Autor braucht einen Namen.");
            }
            maxLength(embed.author().name(), MAX_AUTHOR, prefix + "Der Autorname", errors);
            url(embed.author().url(), prefix + "Der Autor-Link", errors);
            url(embed.author().iconUrl(), prefix + "Das Autor-Bild", errors);
        }
        if (embed.footer() != null) {
            if (isBlank(embed.footer().text()) && !isBlank(embed.footer().iconUrl())) {
                errors.add(prefix + "Der Footer braucht einen Text.");
            }
            maxLength(embed.footer().text(), MAX_FOOTER, prefix + "Der Footer", errors);
            url(embed.footer().iconUrl(), prefix + "Das Footer-Bild", errors);
        }
        if (embed.thumbnail() != null) {
            url(embed.thumbnail().url(), prefix + "Das Thumbnail", errors);
        }
        if (embed.image() != null) {
            url(embed.image().url(), prefix + "Das Bild", errors);
        }
        if (embed.fields().size() > MAX_FIELDS) {
            errors.add(prefix + "Ein Embed kann höchstens " + MAX_FIELDS + " Felder haben.");
        }
        for (int i = 0; i < embed.fields().size(); i++) {
            var field = embed.fields().get(i);
            String fieldPrefix = prefix + "Feld " + (i + 1) + ": ";
            if (isBlank(field.name()) || isBlank(field.value())) {
                errors.add(fieldPrefix + "Name und Wert dürfen nicht leer sein.");
            }
            maxLength(field.name(), MAX_FIELD_NAME, fieldPrefix + "Der Name", errors);
            maxLength(field.value(), MAX_FIELD_VALUE, fieldPrefix + "Der Wert", errors);
        }
    }

    static boolean isEmpty(Embed embed) {
        return isBlank(embed.title())
                && isBlank(embed.description())
                && embed.fields().isEmpty()
                && (embed.author() == null || isBlank(embed.author().name()))
                && (embed.footer() == null || isBlank(embed.footer().text()))
                && (embed.image() == null || isBlank(embed.image().url()))
                && (embed.thumbnail() == null || isBlank(embed.thumbnail().url()));
    }

    private static int textLength(Embed embed) {
        int length = length(embed.title()) + length(embed.description());
        if (embed.author() != null) length += length(embed.author().name());
        if (embed.footer() != null) length += length(embed.footer().text());
        for (var field : embed.fields()) {
            length += length(field.name()) + length(field.value());
        }
        return length;
    }

    private static void maxLength(String value, int max, String label, List<String> errors) {
        if (value != null && value.length() > max) {
            errors.add(label + " darf höchstens " + max + " Zeichen haben.");
        }
    }

    private static void url(String value, String label, List<String> errors) {
        if (isBlank(value)) {
            return;
        }
        if (!(value.startsWith("https://") || value.startsWith("http://")) || value.length() > MAX_URL) {
            errors.add(label + " muss eine gültige http(s)-URL sein.");
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
