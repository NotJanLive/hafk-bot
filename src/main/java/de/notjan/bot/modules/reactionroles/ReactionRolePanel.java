package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.MessagePayload;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record ReactionRolePanel(long id, long guildId, BotMessage message, Type type, Mode mode, List<Option> options) {

    public enum Type {
        BUTTONS,
        SELECT,
        REACTIONS
    }

    public enum Mode {
        NORMAL,
        UNIQUE,
        VERIFY
    }

    public record Option(long roleId, String label, String emoji, String description, String style) {
    }

    public record Draft(long channelId, String label, Type type, Mode mode, MessagePayload payload, List<Option> options) {
    }

    public Set<Long> roleIds() {
        return options.stream().map(Option::roleId).collect(Collectors.toSet());
    }

    public Optional<Option> option(long roleId) {
        return options.stream().filter(option -> option.roleId() == roleId).findFirst();
    }
}
