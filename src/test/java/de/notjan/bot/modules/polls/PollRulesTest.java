package de.notjan.bot.modules.polls;

import de.notjan.bot.modules.polls.Poll.Option;
import de.notjan.bot.modules.polls.Poll.OptionDraft;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.util.UserFacingException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollRulesTest {

    @Test
    void singleChoiceSwitchesAndWithdrawsWhenChangesAreAllowed() {
        assertEquals(Set.of(1L), VoteRules.click(1, true, Set.of(), 1L));
        assertEquals(Set.of(2L), VoteRules.click(1, true, Set.of(1L), 2L));
        assertEquals(Set.of(), VoteRules.click(1, true, Set.of(1L), 1L));
    }

    @Test
    void finalVotesCannotBeChanged() {
        assertThrows(UserFacingException.class, () -> VoteRules.click(1, false, Set.of(1L), 2L));
        assertThrows(UserFacingException.class, () -> VoteRules.click(1, false, Set.of(1L), 1L));
        assertThrows(UserFacingException.class, () -> VoteRules.withdraw(false, Set.of(1L)));
    }

    @Test
    void multipleChoiceAddsUntilTheLimit() {
        assertEquals(Set.of(1L, 2L), VoteRules.click(2, true, Set.of(1L), 2L));
        assertThrows(UserFacingException.class, () -> VoteRules.click(2, true, Set.of(1L, 2L), 3L));
        assertEquals(Set.of(2L), VoteRules.click(2, true, Set.of(1L, 2L), 1L));
        assertEquals(Set.of(1L, 2L), VoteRules.click(3, false, Set.of(1L), 2L));
    }

    @Test
    void withdrawRequiresAVote() {
        assertThrows(UserFacingException.class, () -> VoteRules.withdraw(true, Set.of()));
        assertEquals(Set.of(), VoteRules.withdraw(true, Set.of(1L, 2L)));
    }

    @Test
    void parsesLeadingEmojisAndListMarkers() {
        assertEquals(new OptionDraft("Pizza", "🍕"), PollService.parseOption("🍕 Pizza"));
        assertEquals(new OptionDraft("Minecraft", "<:mc:123456789012345678>"), PollService.parseOption("<:mc:123456789012345678> Minecraft"));
        assertEquals(new OptionDraft("Burger", null), PollService.parseOption("- Burger"));
        assertEquals(new OptionDraft("Döner mit Soße", null), PollService.parseOption("2. Döner mit Soße"));
        assertEquals(new OptionDraft("1 Stunde", null), PollService.parseOption("1 Stunde"));
    }

    @Test
    void mapsRejectedEmojisToTheAffectedAnswer() {
        List<Option> options = IntStream.range(0, 7).mapToObj(i -> new Option(i, "Antwort " + (i + 1), "🎮")).toList();
        Poll poll = new Poll(1, 1, 1, null, "Frage", null, false, ResultVisibility.LIVE, true, 1, true, null, "salt", null, null,
                null, false, 1, Instant.now(), options, Set.of());
        UserFacingException rejected = new UserFacingException("Discord hat die Nachricht abgelehnt: 50035: Invalid Form Body "
                + "components[1].components[1].emoji.name - COMPONENT_INVALID_EMOJI: Invalid emoji");

        assertEquals("Antwort 7 („Antwort 7“): Discord kennt dieses Emoji nicht. Bitte wähle ein anderes.",
                PollService.invalidEmoji(rejected, poll).orElseThrow().getMessage());
        assertTrue(PollService.invalidEmoji(new UserFacingException("Anderer Fehler"), poll).isEmpty());
    }

    @Test
    void stripsUnneededVariationSelectors() {
        assertEquals("🚘", PollService.normalizeEmoji("🚘️"));
        assertEquals("⛏️", PollService.normalizeEmoji("⛏️"));
        assertEquals("🔫", PollService.normalizeEmoji("🔫"));
        assertEquals("<:mc:123456789012345678>", PollService.normalizeEmoji("<:mc:123456789012345678>"));
    }

    @Test
    void rendersPercentagesAndBars() {
        assertEquals(0, PollRenderer.percent(0, 0));
        assertEquals(33, PollRenderer.percent(1, 3));
        assertEquals("▰▰▰▰▰▰▱▱▱▱▱▱", PollRenderer.bar(50));
        assertEquals("▱▱▱▱▱▱▱▱▱▱▱▱", PollRenderer.bar(0));
        assertEquals("▰▰▰▰▰▰▰▰▰▰▰▰", PollRenderer.bar(100));
    }
}
