package de.notjan.bot.modules.embeds;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EmbedCommand implements SlashCommand {

    private static final Pattern MESSAGE_ID = Pattern.compile("(\\d{17,20})/?$");

    private final EmbedService embeds;
    private final AccessService access;

    EmbedCommand(EmbedService embeds, AccessService access) {
        this.embeds = embeds;
        this.access = access;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("embed", "Eingebettete Nachrichten erstellen, senden und bearbeiten")
                .setContexts(InteractionContextType.GUILD)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("create", "Erstellt ein Embed und sendet es in einen Kanal")
                                .addOptions(channelOption()),
                        new SubcommandData("template", "Sendet eine gespeicherte Vorlage in einen Kanal")
                                .addOptions(new OptionData(OptionType.STRING, "name", "Name der Vorlage", true, true), channelOption()),
                        new SubcommandData("edit", "Bearbeitet ein vom Bot gesendetes Embed")
                                .addOption(OptionType.STRING, "message", "Link oder ID der Nachricht", true));
    }

    private static OptionData channelOption() {
        return new OptionData(OptionType.CHANNEL, "channel", "Zielkanal", true).setChannelTypes(ChannelType.TEXT, ChannelType.NEWS);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        requireAccess(access, guild, event.getUser().getIdLong());
        switch (event.getSubcommandName()) {
            case "create" -> event.replyModal(EmbedModalHandler.createModal(event.getOption("channel").getAsChannel().getIdLong())).queue();
            case "template" -> sendTemplate(event, guild);
            case "edit" -> edit(event, guild);
            case null, default -> Replies.error(event, "Unbekannter Befehl.");
        }
    }

    private void sendTemplate(SlashCommandInteractionEvent event, Guild guild) {
        String name = event.getOption("name").getAsString();
        EmbedTemplate template = embeds.findTemplateByName(guild.getIdLong(), name)
                .orElseThrow(() -> new UserFacingException("Es gibt keine Vorlage mit dem Namen „" + name + "“."));
        long channelId = event.getOption("channel").getAsChannel().getIdLong();
        event.deferReply(true).queue();
        BotMessage sent = embeds.send(guild, event.getUser().getIdLong(), Source.DISCORD, channelId, template.name(), template.payload());
        Replies.success(event, "Vorlage „" + template.name() + "“ gesendet: " + sent.jumpUrl());
    }

    private void edit(SlashCommandInteractionEvent event, Guild guild) {
        Matcher matcher = MESSAGE_ID.matcher(event.getOption("message").getAsString().strip());
        if (!matcher.find()) {
            throw new UserFacingException("Bitte gib den Link oder die ID einer Nachricht an.");
        }
        long messageId = Long.parseLong(matcher.group(1));
        BotMessage message = embeds.findSentByMessageId(guild.getIdLong(), messageId)
                .orElseThrow(() -> new UserFacingException("Diese Nachricht wurde nicht über das Embed-Modul gesendet."));
        event.replyModal(EmbedModalHandler.editModal(message)).queue();
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null || access.check(event.getGuild(), event.getUser().getIdLong()).isEmpty()) {
            event.replyChoices().queue();
            return;
        }
        String query = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        var choices = embeds.templates(event.getGuild().getIdLong()).stream()
                .map(EmbedTemplate::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(query))
                .limit(25)
                .map(name -> new Command.Choice(name, name))
                .toList();
        event.replyChoices(choices).queue();
    }

    static void requireAccess(AccessService access, Guild guild, long userId) {
        if (access.check(guild, userId).isEmpty()) {
            throw new UserFacingException("Du hast keinen Zugriff auf die Embed-Verwaltung.");
        }
    }
}
