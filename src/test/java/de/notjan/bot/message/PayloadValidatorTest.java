package de.notjan.bot.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.message.MessagePayload.Embed;
import de.notjan.bot.message.MessagePayload.Field;
import de.notjan.bot.message.MessagePayload.Footer;
import de.notjan.bot.message.MessagePayload.Media;
import de.notjan.bot.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadValidatorTest {

    private static Embed embed(String title, String description) {
        return new Embed(title, description, null, null, null, null, null, null, null, List.of());
    }

    @Test
    void acceptsSimpleEmbed() {
        assertEquals(List.of(), PayloadValidator.validate(new MessagePayload(null, List.of(embed("Regeln", "Seid nett")))));
    }

    @Test
    void rejectsEmptyMessage() {
        assertEquals(1, PayloadValidator.validate(new MessagePayload("  ", List.of())).size());
        assertTrue(PayloadValidator.validate(new MessagePayload(null, List.of(Embed.EMPTY))).getFirst().contains("leer"));
    }

    @Test
    void rejectsTooLongTitleAndInvalidUrls() {
        Embed embed = new Embed("x".repeat(257), null, "ftp://example.org", null, null, null,
                new Footer(null, "https://example.org/icon.png"), null, new Media("not a url"), List.of());

        List<String> errors = PayloadValidator.validate(new MessagePayload(null, List.of(embed)));

        assertEquals(4, errors.size(), errors.toString());
    }

    @Test
    void enforcesTotalTextLimit() {
        Embed big = embed("t", "d".repeat(4000));
        List<String> errors = PayloadValidator.validate(new MessagePayload(null, List.of(big, big)));

        assertTrue(errors.stream().anyMatch(error -> error.contains("6000")), errors.toString());
    }

    @Test
    void rejectsEmptyFieldsAndTooManyFields() {
        Embed embed = new Embed("t", null, null, null, null, null, null, null, null,
                Collections.nCopies(26, new Field("", "", false)));

        List<String> errors = PayloadValidator.validate(new MessagePayload(null, List.of(embed)));

        assertTrue(errors.stream().anyMatch(error -> error.contains("25")), errors.toString());
    }

    @Test
    void readsDiscordJsonWithUnknownProperties() throws Exception {
        ObjectMapper json = Json.mapper();
        String discohook = """
                {"content":"Hallo","embeds":[{"title":"T","color":5991679,"footer":{"text":"F","icon_url":"https://x.org/i.png"},
                "fields":[{"name":"A","value":"B","inline":true}],"type":"rich"}],"attachments":[]}""";

        MessagePayload payload = json.readValue(discohook, MessagePayload.class);

        assertEquals("https://x.org/i.png", payload.firstEmbed().footer().iconUrl());
        assertTrue(payload.firstEmbed().fields().getFirst().inline());
        assertTrue(json.writeValueAsString(payload).contains("\"icon_url\""));
    }
}
