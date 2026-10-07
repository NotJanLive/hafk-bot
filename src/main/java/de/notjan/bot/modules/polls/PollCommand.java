package de.notjan.bot.modules.polls;

import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.modules.polls.PollComponentHandler.CreateSettings;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.function.Function;

final class PollCommand implements SlashCommand {

    private static final int DEFAULT_DURATION = 60 * 24;

    private final PollService service;

    PollCommand(PollService service) {
        this.service = service;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("poll", "Umfragen starten")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(new SubcommandData("create", "Startet eine Umfrage in diesem Kanal").addOptions(
                        new OptionData(OptionType.BOOLEAN, "anonymous", "Anonym: Niemand sieht, wer wofür gestimmt hat (Standard: nein)"),
                        new OptionData(OptionType.STRING, "results", "Wann Mitglieder das Ergebnis sehen (Standard: live)")
                                .addChoice("Live für alle", ResultVisibility.LIVE.name())
                                .addChoice("Nach der eigenen Stimme", ResultVisibility.AFTER_VOTE.name())
                                .addChoice("Erst nach dem Ende", ResultVisibility.CLOSED.name()),
                        new OptionData(OptionType.INTEGER, "duration", "Laufzeit der Umfrage (Standard: 1 Tag)")
                                .addChoice("1 Stunde", 60)
                                .addChoice("6 Stunden", 360)
                                .addChoice("12 Stunden", 720)
                                .addChoice("1 Tag", 1440)
                                .addChoice("3 Tage", 4320)
                                .addChoice("1 Woche", 10080)
                                .addChoice("2 Wochen", 20160)
                                .addChoice("Ohne Ende", 0),
                        new OptionData(OptionType.INTEGER, "max_choices", "Wie viele Antworten jede Person wählen darf (Standard: 1)")
                                .setRequiredRange(1, PollService.MAX_OPTIONS),
                        new OptionData(OptionType.BOOLEAN, "allow_change", "Stimme ändern oder zurückziehen erlaubt (Standard: ja)"),
                        new OptionData(OptionType.BOOLEAN, "interim_results",
                                "Zwischenstände für dich und das Team sichtbar, sonst Überraschung (Standard: ja)"),
                        new OptionData(OptionType.ROLE, "ping", "Rolle, die beim Start und beim Ende gepingt wird")));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        if (!"create".equals(event.getSubcommandName())) {
            Replies.error(event, "Unbekannter Befehl.");
            return;
        }
        if (!service.canCreate(event.getMember())) {
            throw new UserFacingException("Du darfst auf diesem Server keine Umfragen erstellen.");
        }
        if (!(event.getChannel() instanceof GuildMessageChannel channel)) {
            throw new UserFacingException("In diesem Kanal können keine Umfragen gestartet werden.");
        }
        CreateSettings settings = new CreateSettings(
                channel.getIdLong(),
                option(event, "anonymous", OptionMapping::getAsBoolean, false),
                option(event, "results", mapping -> ResultVisibility.valueOf(mapping.getAsString()), ResultVisibility.LIVE),
                option(event, "duration", OptionMapping::getAsInt, DEFAULT_DURATION),
                option(event, "max_choices", OptionMapping::getAsInt, 1),
                option(event, "allow_change", OptionMapping::getAsBoolean, true),
                option(event, "interim_results", OptionMapping::getAsBoolean, true),
                option(event, "ping", mapping -> mapping.getAsRole().getIdLong(), 0L));
        event.replyModal(PollComponentHandler.createModal(settings)).queue();
    }

    private static <T> T option(SlashCommandInteractionEvent event, String name, Function<OptionMapping, T> mapper, T fallback) {
        OptionMapping mapping = event.getOption(name);
        return mapping == null ? fallback : mapper.apply(mapping);
    }
}
