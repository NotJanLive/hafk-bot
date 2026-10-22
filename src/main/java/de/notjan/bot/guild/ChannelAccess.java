package de.notjan.bot.guild;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IPermissionHolder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.concrete.Category;

import java.util.Collection;
import java.util.EnumSet;

public final class ChannelAccess {

    public enum Visibility {
        PUBLIC,
        READ_ONLY,
        PRIVATE
    }

    private static final EnumSet<Permission> TEAM = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_ADD_REACTION);
    private static final EnumSet<Permission> BOT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.MANAGE_CHANNEL);
    private static final EnumSet<Permission> READ_ONLY_DENY = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS,
            Permission.CREATE_PUBLIC_THREADS, Permission.CREATE_PRIVATE_THREADS);

    private ChannelAccess() {
    }

    public static boolean canManage(Guild guild) {
        Member self = guild.getSelfMember();
        return self.hasPermission(Permission.MANAGE_ROLES) && self.hasPermission(Permission.MANAGE_CHANNEL);
    }

    public static void apply(IPermissionContainer channel, Visibility visibility, Collection<Role> roles) {
        if (visibility == Visibility.PUBLIC) {
            return;
        }
        Guild guild = channel.getGuild();
        Member self = guild.getSelfMember();
        boolean category = channel instanceof Category;
        if (visibility == Visibility.PRIVATE) {
            put(channel, guild.getPublicRole(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.VIEW_CHANNEL));
            for (Role role : roles) {
                if (!role.isPublicRole()) {
                    put(channel, role, grantable(self, TEAM), EnumSet.noneOf(Permission.class));
                }
            }
        } else if (!category) {
            put(channel, guild.getPublicRole(), EnumSet.noneOf(Permission.class), READ_ONLY_DENY);
        }
        put(channel, self, grantable(self, BOT), EnumSet.noneOf(Permission.class));
    }

    private static void put(IPermissionContainer channel, IPermissionHolder holder, EnumSet<Permission> allow, EnumSet<Permission> deny) {
        PermissionOverride current = channel.getPermissionOverride(holder);
        EnumSet<Permission> allowed = current == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(current.getAllowed());
        EnumSet<Permission> denied = current == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(current.getDenied());
        allowed.addAll(allow);
        allowed.removeAll(deny);
        denied.addAll(deny);
        denied.removeAll(allow);
        channel.upsertPermissionOverride(holder).setAllowed(allowed).setDenied(denied).reason("Rechte im Dashboard gesetzt").complete();
    }

    private static EnumSet<Permission> grantable(Member self, EnumSet<Permission> wanted) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        wanted.stream().filter(permission -> self.hasPermission(Permission.ADMINISTRATOR) || self.hasPermission(permission))
                .forEach(result::add);
        result.add(Permission.VIEW_CHANNEL);
        return result;
    }
}
