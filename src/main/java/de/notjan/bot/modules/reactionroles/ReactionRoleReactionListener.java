package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Type;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.react.GenericMessageReactionEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

final class ReactionRoleReactionListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ReactionRoleReactionListener.class);

    private final ReactionRoleService service;

    ReactionRoleReactionListener(ReactionRoleService service) {
        this.service = service;
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        handle(event, event.getEmoji(), (member, panel) -> service.reactionAdded(member, panel, event.getEmoji()));
    }

    @Override
    public void onMessageReactionRemove(MessageReactionRemoveEvent event) {
        handle(event, event.getEmoji(), (member, panel) -> service.reactionRemoved(member, panel, event.getEmoji()));
    }

    private void handle(GenericMessageReactionEvent event, Emoji emoji, BiConsumer<Member, ReactionRolePanel> action) {
        if (!event.isFromGuild()
                || event.getUserIdLong() == event.getJDA().getSelfUser().getIdLong()
                || !service.isReactionPanel(event.getMessageIdLong())) {
            return;
        }
        service.findByDiscordMessage(event.getMessageIdLong())
                .filter(panel -> panel.type() == Type.REACTIONS)
                .ifPresent(panel -> event.retrieveMember().queue(member -> {
                    if (member.getUser().isBot()) {
                        return;
                    }
                    try {
                        action.accept(member, panel);
                    } catch (RuntimeException e) {
                        LOG.warn("Reaction role {} failed for {} in guild {}", emoji.getFormatted(), member.getId(),
                                member.getGuild().getId(), e);
                    }
                }, error -> LOG.debug("Could not resolve reacting member", error)));
    }
}
