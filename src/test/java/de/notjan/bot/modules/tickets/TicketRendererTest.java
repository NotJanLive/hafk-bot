package de.notjan.bot.modules.tickets;

import de.notjan.bot.modules.tickets.Ticket.Status;
import de.notjan.bot.modules.tickets.TicketConfig.ButtonColor;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.FieldType;
import de.notjan.bot.modules.tickets.TicketConfig.FormField;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketRendererTest {

    private static Ticket ticket(int number, String category) {
        return new Ticket(1, "00000000-0000-0000-0000-000000000000", 1, number, 1L, category, 1L, "", null, null, null, List.of(), 2,
                "max", 2, null, Status.OPEN, null, null, null, null, null, null, Instant.now(), null, null, null);
    }

    private static Category category(int fields) {
        FormField field = new FormField(0, FieldType.SHORT, "Frage", null, null, true, null, null, null, null, List.of());
        return new Category(1, 1, "Support", null, null, ButtonColor.PRIMARY, null, "{category}-{number}", null, null, null, 1, 0, true,
                true, false, null, null, null, false, 0, Set.of(), Set.of(), Set.of(), Collections.nCopies(fields, field));
    }

    @Test
    void buildsChannelNamesFromPattern() {
        assertEquals("support-0042", TicketRenderer.channelName("{category}-{number}", ticket(42, "Support"), "max"));
        assertEquals("bewerbung-max_99", TicketRenderer.channelName("{category} {username}", ticket(1, "Bewerbung!"), "Max_99"));
        assertEquals("report-0007", TicketRenderer.channelName("  ", ticket(7, "Report"), "max"));
    }

    @Test
    void fallsBackWhenNothingIsLeft() {
        assertEquals("ticket-5", TicketRenderer.channelName("!!!", ticket(5, "x"), "max"));
        assertTrue(TicketRenderer.channelName("{category}", ticket(5, "a".repeat(150)), "max").length() <= 100);
    }

    @Test
    void formatsDurations() {
        assertEquals("unter 1 Minute", TicketRenderer.duration(Duration.ofSeconds(20)));
        assertEquals("45 Min.", TicketRenderer.duration(Duration.ofMinutes(45)));
        assertEquals("2 Std. 5 Min.", TicketRenderer.duration(Duration.ofMinutes(125)));
        assertEquals("1 Tag 3 Std.", TicketRenderer.duration(Duration.ofHours(27)));
    }

    @Test
    void sanitizesStoredFileNames() {
        assertEquals("Screenshot__1_.png", AssetStore.safeName("Screenshot (1).png"));
        assertEquals("_.._.._etc_passwd", AssetStore.safeName("../../etc/passwd"));
        assertEquals("file", AssetStore.safeName(" "));
        assertFalse(AssetStore.safeName("x".repeat(300)).length() > 120);
    }

    @Test
    void splitsFormsIntoPagesOfFive() {
        assertEquals(0, TicketForms.pages(category(0)));
        assertEquals(1, TicketForms.pages(category(5)));
        assertEquals(2, TicketForms.pages(category(6)));
        assertEquals(5, TicketForms.pages(category(25)));
    }
}
