package de.notjan.bot.modules.tickets;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.modules.tickets.TicketConfig.BlacklistEntry;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.Target;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

final class TicketCommand implements SlashCommand {

    private static final Logger LOG = LoggerFactory.getLogger(TicketCommand.class);

    private final TicketService service;

    TicketCommand(TicketService service) {
        this.service = service;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("ticket", "Tickets verwalten")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(
                        new SubcommandData("close", "Schließt dieses Ticket")
                                .addOption(OptionType.STRING, "reason", "Grund für das Schließen", false),
                        new SubcommandData("close-request", "Fragt nach, ob das Ticket geschlossen werden kann")
                                .addOptions(new OptionData(OptionType.INTEGER, "hours",
                                                "Nach wie vielen Stunden ohne Antwort automatisch schließen (Standard aus dem Dashboard)")
                                                .setRequiredRange(1, TicketService.MAX_CLOSE_REQUEST_HOURS),
                                        new OptionData(OptionType.STRING, "reason", "Grund, z. B. „Problem gelöst“")),
                        new SubcommandData("claim", "Übernimmt dieses Ticket"),
                        new SubcommandData("unclaim", "Gibt dieses Ticket wieder frei"),
                        new SubcommandData("transfer", "Übergibt dieses Ticket an jemand anderen")
                                .addOption(OptionType.USER, "member", "Wer das Ticket übernehmen soll", true),
                        new SubcommandData("add", "Fügt eine Person oder Rolle zum Ticket hinzu")
                                .addOption(OptionType.MENTIONABLE, "target", "Person oder Rolle", true),
                        new SubcommandData("remove", "Entfernt eine Person oder Rolle aus dem Ticket")
                                .addOption(OptionType.MENTIONABLE, "target", "Person oder Rolle", true),
                        new SubcommandData("rename", "Benennt den Ticket-Kanal um")
                                .addOption(OptionType.STRING, "name", "Neuer Kanalname", true),
                        new SubcommandData("move", "Verschiebt das Ticket in eine andere Kategorie")
                                .addOption(OptionType.STRING, "category", "Neue Kategorie", true, true),
                        new SubcommandData("notes", "Öffnet die privaten Team-Notizen zu diesem Ticket"),
                        new SubcommandData("open", "Öffnet ein Ticket für ein Mitglied")
                                .addOption(OptionType.USER, "member", "Für wen das Ticket ist", true)
                                .addOption(OptionType.STRING, "category", "Kategorie", true, true))
                .addSubcommandGroups(new SubcommandGroupData("blacklist", "Sperren für das Ticketsystem verwalten").addSubcommands(
                        new SubcommandData("add", "Sperrt eine Person oder Rolle für Tickets")
                                .addOption(OptionType.MENTIONABLE, "target", "Person oder Rolle", true)
                                .addOption(OptionType.STRING, "reason", "Grund der Sperre", false)
                                .addOptions(new OptionData(OptionType.INTEGER, "days", "Dauer in Tagen (leer = dauerhaft)")
                                        .setRequiredRange(1, 3650)),
                        new SubcommandData("remove", "Hebt eine Sperre auf")
                                .addOption(OptionType.MENTIONABLE, "target", "Person oder Rolle", true),
                        new SubcommandData("list", "Zeigt alle aktiven Sperren")));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(null, error -> LOG.warn("Could not acknowledge /ticket", error));
        Member member = event.getMember();
        if ("blacklist".equals(event.getSubcommandGroup())) {
            blacklist(event, member);
            return;
        }
        switch (event.getSubcommandName() == null ? "" : event.getSubcommandName()) {
            case "close" -> {
                Ticket ticket = service.requireByChannel(event.getGuildChannel());
                service.requireCanClose(ticket, member);
                service.close(event.getGuild(), ticket, member.getIdLong(), string(event, "reason"));
                Replies.success(event, "🔒 Das Ticket wird geschlossen.");
            }
            case "close-request" -> {
                Ticket ticket = service.requireByChannel(event.getGuildChannel());
                OptionMapping hours = event.getOption("hours");
                service.requestClose(ticket, member, hours == null ? null : hours.getAsInt(), string(event, "reason"));
                Replies.success(event, "📨 Schließantrag gesendet.");
            }
            case "claim" -> {
                service.claim(service.requireByChannel(event.getGuildChannel()), member);
                Replies.success(event, "🙋 Du bearbeitest jetzt dieses Ticket.");
            }
            case "unclaim" -> {
                service.unclaim(service.requireByChannel(event.getGuildChannel()), member);
                Replies.success(event, "↩️ Das Ticket ist wieder frei.");
            }
            case "transfer" -> {
                Member target = event.getOption("member", OptionMapping::getAsMember);
                if (target == null) {
                    throw new UserFacingException("Diese Person ist nicht auf dem Server.");
                }
                service.transfer(service.requireByChannel(event.getGuildChannel()), member, target);
                Replies.success(event, "🔁 Das Ticket wurde an " + target.getAsMention() + " übergeben.");
            }
            case "add" -> {
                Target target = target(event);
                service.add(service.requireByChannel(event.getGuildChannel()), member, target);
                Replies.success(event, "➕ Hinzugefügt.");
            }
            case "remove" -> {
                Target target = target(event);
                service.remove(service.requireByChannel(event.getGuildChannel()), member, target);
                Replies.success(event, "➖ Entfernt.");
            }
            case "rename" -> {
                service.rename(service.requireByChannel(event.getGuildChannel()), member, string(event, "name"));
                Replies.success(event, "✏️ Der Kanal wurde umbenannt.");
            }
            case "move" -> {
                Category category = category(event);
                service.move(service.requireByChannel(event.getGuildChannel()), member, category);
                Replies.success(event, "📂 Das Ticket ist jetzt in „" + category.name() + "“.");
            }
            case "notes" -> {
                ThreadChannel thread = service.notes(service.requireByChannel(event.getGuildChannel()), member);
                Replies.success(event, "📝 Team-Notizen: " + thread.getAsMention());
            }
            case "open" -> {
                Category category = category(event);
                if (!service.isStaff(member, category)) {
                    throw new UserFacingException("Nur das Team kann Tickets für andere öffnen.");
                }
                Member target = event.getOption("member", OptionMapping::getAsMember);
                if (target == null || target.getUser().isBot()) {
                    throw new UserFacingException("Wähle ein Mitglied dieses Servers aus.");
                }
                Ticket ticket = service.open(event.getGuild(), target, member, category, List.of(), List.of());
                Replies.success(event, "🎫 Ticket für " + target.getAsMention() + " erstellt: <#" + ticket.channelId() + ">");
            }
            default -> Replies.error(event, "Unbekannter Befehl.");
        }
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null || !"category".equals(event.getFocusedOption().getName())) {
            event.replyChoices().queue();
            return;
        }
        String query = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = service.categories(event.getGuild().getIdLong()).stream()
                .filter(category -> category.name().toLowerCase(Locale.ROOT).contains(query))
                .limit(25)
                .map(category -> new Command.Choice(category.name(), String.valueOf(category.id())))
                .toList();
        event.replyChoices(choices).queue();
    }

    private void blacklist(SlashCommandInteractionEvent event, Member member) {
        if (!service.isStaff(member, (Category) null)) {
            throw new UserFacingException("Sperren kann nur das Team verwalten.");
        }
        switch (event.getSubcommandName() == null ? "" : event.getSubcommandName()) {
            case "add" -> {
                Target target = target(event);
                OptionMapping days = event.getOption("days");
                service.blacklistAdd(event.getGuild(), member.getIdLong(), Source.DISCORD, target, string(event, "reason"),
                        days == null ? null : days.getAsInt());
                Replies.success(event, "⛔ " + mention(target) + " ist jetzt vom Ticketsystem gesperrt"
                        + (days == null ? "." : " (" + days.getAsInt() + " Tage)."));
            }
            case "remove" -> {
                Target target = target(event);
                service.blacklistRemove(event.getGuild(), member.getIdLong(), Source.DISCORD, target);
                Replies.success(event, "✅ Die Sperre für " + mention(target) + " wurde aufgehoben.");
            }
            case "list" -> {
                List<BlacklistEntry> entries = service.blacklist(event.getGuild().getIdLong());
                Replies.success(event, entries.isEmpty()
                        ? "Niemand ist vom Ticketsystem gesperrt."
                        : "**Gesperrt (" + entries.size() + ")**\n" + entries.stream().limit(30).map(entry ->
                                "• " + mention(new Target(entry.type(), entry.targetId()))
                                        + (entry.expiresAt() == null ? " · dauerhaft" : " · bis <t:" + entry.expiresAt().getEpochSecond() + ":d>")
                                        + (entry.reason() == null ? "" : " · " + entry.reason()))
                        .collect(Collectors.joining("\n")));
            }
            default -> Replies.error(event, "Unbekannter Befehl.");
        }
    }

    private Category category(SlashCommandInteractionEvent event) {
        String value = string(event, "category");
        try {
            return service.requireCategory(event.getGuild(), Long.parseLong(value));
        } catch (NumberFormatException e) {
            return service.categories(event.getGuild().getIdLong()).stream()
                    .filter(category -> category.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new UserFacingException("Die Kategorie „" + value + "“ gibt es nicht."));
        }
    }

    private static Target target(SlashCommandInteractionEvent event) {
        IMentionable mentionable = event.getOption("target", OptionMapping::getAsMentionable);
        return switch (mentionable) {
            case Role role -> new Target(TargetType.ROLE, role.getIdLong());
            case Member member -> new Target(TargetType.USER, member.getIdLong());
            case User user -> new Target(TargetType.USER, user.getIdLong());
            case null, default -> throw new UserFacingException("Wähle eine Person oder Rolle aus.");
        };
    }

    private static String mention(Target target) {
        return target.type() == TargetType.ROLE ? "<@&" + target.id() + ">" : "<@" + target.id() + ">";
    }

    private static String string(SlashCommandInteractionEvent event, String name) {
        OptionMapping mapping = event.getOption(name);
        if (mapping == null) {
            return null;
        }
        String value = mapping.getAsString().strip();
        return value.isEmpty() ? null : value;
    }
}
