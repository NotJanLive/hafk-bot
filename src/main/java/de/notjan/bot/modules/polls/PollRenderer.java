package de.notjan.bot.modules.polls;

import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.message.MessagePayload.Author;
import de.notjan.bot.message.MessagePayload.Embed;
import de.notjan.bot.message.MessagePayload.Footer;
import de.notjan.bot.modules.polls.Poll.Option;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.modules.polls.PollRepository.Tally;
import de.notjan.bot.util.Brand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class PollRenderer {

    static final int CLOSED_COLOR = 0x4E5058;
    private static final int BAR_LENGTH = 12;
    static final int BUTTONS_PER_ROW = 5;
    private static final int MAX_TEXT = 4000;
    private static final int VOTER_BUDGET = 60;

    private PollRenderer() {
    }

    static MessagePayload message(Poll poll, Tally tally) {
        List<String> parts = new ArrayList<>();
        String timing = timing(poll);
        if (timing != null) {
            parts.add(timing);
        }
        if (!isBlank(poll.description())) {
            parts.add(poll.description().strip());
        }
        boolean showResults = poll.membersSeeResults(false);
        parts.add(optionLines(poll, tally, showResults, showResults && !poll.anonymous()));
        parts.add("-# " + String.join(" · ", meta(poll)));

        String state = poll.cancelled() ? " · Abgebrochen" : poll.isOpen() ? "" : " · Beendet";
        String author = (poll.anonymous() ? "🔒 Anonyme Umfrage" : "📊 Umfrage") + state;
        String footer = participants(tally.voters()) + " · "
                + (poll.anonymous() ? "Diese Umfrage ist anonym" : "Diese Umfrage ist öffentlich");
        Embed embed = new Embed(poll.question(), truncate(String.join("\n\n", parts)), null,
                poll.isOpen() ? primaryColor() : CLOSED_COLOR, null,
                new Author(author, null, null), new Footer(footer, null), null, null, List.of());
        return new MessagePayload(ping(poll), List.of(embed));
    }

    static MessagePayload endNotice(Poll poll, Tally tally, String jumpUrl) {
        String content = (poll.pingRoleId() == null ? "" : "<@&" + poll.pingRoleId() + "> ")
                + "🏁 Die Umfrage **" + poll.question() + "** ist beendet.";
        Embed embed = new Embed("Ergebnis: " + truncateTitle(poll.question()), truncate(optionLines(poll, tally, true, !poll.anonymous())), jumpUrl,
                primaryColor(), null, null, new Footer(participants(tally.voters()), null), null, null, List.of());
        return new MessagePayload(truncateContent(content), List.of(embed));
    }

    static List<ActionRow> components(Poll poll) {
        List<ActionRow> rows = new ArrayList<>();
        if (!poll.isOpen()) {
            return rows;
        }
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < poll.options().size(); i++) {
            Option option = poll.options().get(i);
            buttons.add(Button.of(ButtonStyle.SECONDARY, ComponentId.of(PollService.NAMESPACE, "v", poll.id(), option.id()).toString(),
                    option.label(), isBlank(option.emoji()) ? null : Emoji.fromFormatted(option.emoji())));
        }
        for (int i = 0; i < buttons.size(); i += BUTTONS_PER_ROW) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(i + BUTTONS_PER_ROW, buttons.size()))));
        }
        rows.add(ActionRow.of(Button.primary(ComponentId.of(PollService.NAMESPACE, "m", poll.id()).toString(), "Meine Stimme")
                .withEmoji(Emoji.fromUnicode("🗳️"))));
        return rows;
    }

    static String results(Poll poll, Tally tally, boolean withVoters) {
        return participants(tally.voters()) + "\n\n" + optionLines(poll, tally, true, withVoters && !poll.anonymous());
    }

    static String choices(Poll poll, Set<Long> choices) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < poll.options().size(); i++) {
            Option option = poll.options().get(i);
            if (choices.contains(option.id())) {
                lines.add(emoji(option, i) + " **" + option.label() + "**");
            }
        }
        return String.join("\n", lines);
    }

    static String emoji(Option option, int index) {
        if (!isBlank(option.emoji())) {
            return option.emoji();
        }
        return new String(Character.toChars(0x1F1E6 + index));
    }

    static String bar(int percent) {
        int filled = Math.round(percent * BAR_LENGTH / 100f);
        return "▰".repeat(filled) + "▱".repeat(BAR_LENGTH - filled);
    }

    static int percent(int votes, int voters) {
        return voters == 0 ? 0 : Math.round(votes * 100f / voters);
    }

    static String truncate(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }

    static String timing(Poll poll) {
        if (poll.cancelled()) {
            return "✖️ Abgebrochen <t:" + poll.closedAt().getEpochSecond() + ":R>";
        }
        if (!poll.isOpen()) {
            return "🏁 Beendet <t:" + poll.closedAt().getEpochSecond() + ":R>";
        }
        if (poll.endsAt() != null) {
            long epoch = poll.endsAt().getEpochSecond();
            return "⏳ Endet <t:" + epoch + ":R> · <t:" + epoch + ":f>";
        }
        return null;
    }

    private static String optionLines(Poll poll, Tally tally, boolean showResults, boolean withVoters) {
        int best = poll.options().stream().mapToInt(option -> tally.votes(option.id())).max().orElse(0);
        int votersPerOption = Math.max(3, VOTER_BUDGET / Math.max(1, poll.options().size()));
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < poll.options().size(); i++) {
            Option option = poll.options().get(i);
            int votes = tally.votes(option.id());
            StringBuilder block = new StringBuilder(emoji(option, i)).append(" **").append(option.label()).append("**");
            if (showResults) {
                if (!poll.isOpen() && best > 0 && votes == best) {
                    block.append(" 🏆");
                }
                int percent = percent(votes, tally.voters());
                block.append("\n").append(bar(percent)).append(" **").append(percent).append(" %** · ")
                        .append(votes).append(votes == 1 ? " Stimme" : " Stimmen");
                if (withVoters) {
                    List<Long> voters = tally.voters(option.id());
                    if (!voters.isEmpty()) {
                        String mentions = voters.stream().limit(votersPerOption).map(id -> "<@" + id + ">")
                                .collect(Collectors.joining(", "));
                        int hidden = voters.size() - votersPerOption;
                        block.append("\n-# ").append(mentions).append(hidden > 0 ? " und " + hidden + " weitere" : "");
                    }
                }
            }
            blocks.add(block.toString());
        }
        return String.join("\n\n", blocks);
    }

    private static List<String> meta(Poll poll) {
        List<String> parts = new ArrayList<>();
        parts.add(poll.maxChoices() == 1 ? "Eine Antwort" : "Bis zu " + poll.maxChoices() + " Antworten");
        if (poll.isOpen()) {
            if (poll.endsAt() == null) {
                parts.add("Ohne Enddatum");
            }
            if (poll.visibility() == ResultVisibility.AFTER_VOTE) {
                parts.add("Ergebnis nach deiner Stimme");
            } else if (poll.visibility() == ResultVisibility.CLOSED) {
                parts.add("Ergebnis nach dem Ende");
            }
            if (!poll.allowChange()) {
                parts.add("Stimme ist endgültig");
            }
            if (!poll.allowedRoleIds().isEmpty()) {
                parts.add("Nur für " + poll.allowedRoleIds().stream().map(id -> "<@&" + id + ">").collect(Collectors.joining(", ")));
            }
        } else if (poll.cancelled()) {
            parts.add("Es wird kein Ergebnis veröffentlicht");
        }
        return parts;
    }

    private static String ping(Poll poll) {
        return poll.pingRoleId() == null ? null : "<@&" + poll.pingRoleId() + ">";
    }

    private static int primaryColor() {
        return Brand.PRIMARY.getRGB() & 0xFFFFFF;
    }

    private static String participants(int voters) {
        return voters + (voters == 1 ? " Teilnahme" : " Teilnahmen");
    }

    private static String truncateTitle(String text) {
        return text.length() <= 240 ? text : text.substring(0, 239) + "…";
    }

    private static String truncateContent(String text) {
        return text.length() <= 2000 ? text : text.substring(0, 1999) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
