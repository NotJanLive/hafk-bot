package de.notjan.bot.modules.tickets;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TicketPermissions {

    record Overwrite(boolean role, long id, EnumSet<Permission> allow, EnumSet<Permission> deny) {
    }

    private static final EnumSet<Permission> PARTICIPANT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION,
            Permission.MESSAGE_EXT_EMOJI);
    private static final EnumSet<Permission> READ_ONLY = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
    private static final EnumSet<Permission> BOT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_MANAGE,
            Permission.MANAGE_CHANNEL, Permission.CREATE_PRIVATE_THREADS, Permission.MESSAGE_SEND_IN_THREADS, Permission.MANAGE_THREADS);

    private final AccessService access;

    TicketPermissions(AccessService access) {
        this.access = access;
    }

    boolean isStaff(Member member, Settings settings, Category category) {
        if (access.check(member.getGuild(), member.getIdLong()).isPresent()) {
            return true;
        }
        boolean global = usesGlobalTeam(category);
        if (global && settings.staffUserIds().contains(member.getIdLong())) {
            return true;
        }
        Set<Long> roles = new HashSet<>(global ? settings.staffRoleIds() : Set.of());
        if (category != null) {
            roles.addAll(category.supportRoleIds());
        }
        return member.getRoles().stream().map(ISnowflake::getIdLong).anyMatch(roles::contains);
    }

    static boolean usesGlobalTeam(Category category) {
        return category == null || category.includeStaff();
    }

    boolean isManager(Member member) {
        return access.check(member.getGuild(), member.getIdLong()).isPresent();
    }

    Map<Long, Overwrite> overwrites(Guild guild, Ticket ticket, Settings settings, Category category, List<Ticket.Member> members) {
        Map<Long, Overwrite> result = new LinkedHashMap<>();
        Member self = guild.getSelfMember();
        EnumSet<Permission> participant = grantable(self, PARTICIPANT);
        boolean locked = settings.claimLock() && ticket.claimedBy() != null;
        result.put(guild.getPublicRole().getIdLong(),
                new Overwrite(true, guild.getPublicRole().getIdLong(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.VIEW_CHANNEL)));

        boolean global = usesGlobalTeam(category);
        Set<Long> staffRoles = new HashSet<>();
        if (global) {
            staffRoles.addAll(settings.staffRoleIds());
            staffRoles.addAll(access.dashboardRoleIds(guild));
        }
        if (category != null) {
            staffRoles.addAll(category.supportRoleIds());
        }
        for (long roleId : staffRoles) {
            if (guild.getRoleById(roleId) == null) {
                continue;
            }
            result.put(roleId, locked
                    ? new Overwrite(true, roleId, grantable(self, READ_ONLY), EnumSet.of(Permission.MESSAGE_SEND))
                    : new Overwrite(true, roleId, participant, EnumSet.noneOf(Permission.class)));
        }
        for (long userId : global ? settings.staffUserIds() : Set.<Long>of()) {
            if (!isMember(guild, userId)) {
                continue;
            }
            result.put(userId, locked
                    ? new Overwrite(false, userId, grantable(self, READ_ONLY), EnumSet.of(Permission.MESSAGE_SEND))
                    : new Overwrite(false, userId, participant, EnumSet.noneOf(Permission.class)));
        }
        for (Ticket.Member member : members) {
            boolean role = member.type() == TargetType.ROLE;
            if (role ? guild.getRoleById(member.targetId()) == null : !isMember(guild, member.targetId())) {
                continue;
            }
            result.put(member.targetId(), new Overwrite(role, member.targetId(), participant, EnumSet.noneOf(Permission.class)));
        }
        if (ticket.claimedBy() != null) {
            result.put(ticket.claimedBy(), new Overwrite(false, ticket.claimedBy(), participant, EnumSet.noneOf(Permission.class)));
        }
        result.put(ticket.creatorId(), new Overwrite(false, ticket.creatorId(), participant, EnumSet.noneOf(Permission.class)));
        result.put(self.getIdLong(), new Overwrite(false, self.getIdLong(), grantable(self, BOT), EnumSet.noneOf(Permission.class)));
        return result;
    }

    static ChannelAction<TextChannel> apply(ChannelAction<TextChannel> action, Map<Long, Overwrite> overwrites) {
        for (Overwrite overwrite : overwrites.values()) {
            if (overwrite.role()) {
                action = action.addRolePermissionOverride(overwrite.id(), overwrite.allow(), overwrite.deny());
            } else {
                action = action.addMemberPermissionOverride(overwrite.id(), overwrite.allow(), overwrite.deny());
            }
        }
        return action;
    }

    static void sync(TextChannel channel, Map<Long, Overwrite> overwrites) {
        var manager = channel.getManager();
        for (Overwrite overwrite : overwrites.values()) {
            if (overwrite.role()) {
                manager = manager.putRolePermissionOverride(overwrite.id(), overwrite.allow(), overwrite.deny());
            } else {
                manager = manager.putMemberPermissionOverride(overwrite.id(), overwrite.allow(), overwrite.deny());
            }
        }
        for (PermissionOverride existing : channel.getPermissionOverrides()) {
            if (!overwrites.containsKey(existing.getIdLong())) {
                manager = manager.removePermissionOverride(existing.getIdLong());
            }
        }
        manager.complete();
    }

    private static boolean isMember(Guild guild, long userId) {
        if (guild.getMemberById(userId) != null) {
            return true;
        }
        try {
            guild.retrieveMemberById(userId).complete();
            return true;
        } catch (ErrorResponseException e) {
            return false;
        }
    }

    private static EnumSet<Permission> grantable(Member self, EnumSet<Permission> wanted) {
        if (self.hasPermission(Permission.ADMINISTRATOR)) {
            return EnumSet.copyOf(wanted);
        }
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        wanted.stream().filter(self::hasPermission).forEach(result::add);
        if (result.isEmpty()) {
            result.add(Permission.VIEW_CHANNEL);
        }
        return result;
    }
}
