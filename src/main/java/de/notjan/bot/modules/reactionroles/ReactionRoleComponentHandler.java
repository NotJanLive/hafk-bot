package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.util.Set;
import java.util.stream.Collectors;

final class ReactionRoleComponentHandler implements ComponentHandler {

    private final ReactionRoleService service;

    ReactionRoleComponentHandler(ReactionRoleService service) {
        this.service = service;
    }

    @Override
    public String namespace() {
        return ReactionRoleService.NAMESPACE;
    }

    @Override
    public void onButton(ButtonInteractionEvent event, ComponentId id) {
        var panel = service.findByDiscordMessage(event.getMessageIdLong())
                .orElseThrow(() -> new UserFacingException("Dieses Panel ist nicht mehr aktiv."));
        event.deferReply(true).queue();
        Replies.success(event, service.toggle(event.getMember(), panel, id.longArg(0)));
    }

    @Override
    public void onStringSelect(StringSelectInteractionEvent event, ComponentId id) {
        var panel = service.findByDiscordMessage(event.getMessageIdLong())
                .orElseThrow(() -> new UserFacingException("Dieses Panel ist nicht mehr aktiv."));
        Set<Long> selected;
        try {
            selected = event.getValues().stream().map(Long::parseLong).collect(Collectors.toSet());
        } catch (NumberFormatException e) {
            throw new UserFacingException("Ungültige Auswahl.");
        }
        event.deferReply(true).queue();
        Replies.success(event, service.select(event.getMember(), panel, selected));
    }
}
