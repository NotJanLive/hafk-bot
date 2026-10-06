package de.notjan.bot.api.dto;

import de.notjan.bot.access.DashboardAccess.Grant;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPositionableChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.util.Comparator;
import java.util.List;

public final class GuildDto {

    private GuildDto() {
    }

    public record Summary(String id, String name, String iconUrl, int memberCount, boolean setupCompleted, Grant grant) {

        public static Summary of(Guild guild, boolean setupCompleted, Grant grant) {
            return new Summary(guild.getId(), guild.getName(), guild.getIconUrl(), guild.getMemberCount(), setupCompleted, grant);
        }
    }

    public record Detail(
            String id,
            String name,
            String iconUrl,
            int memberCount,
            Grant grant,
            BotMember bot,
            List<Channel> channels,
            List<RoleView> roles
    ) {

        public static Detail of(Guild guild, Grant grant) {
            return new Detail(
                    guild.getId(),
                    guild.getName(),
                    guild.getIconUrl(),
                    guild.getMemberCount(),
                    grant,
                    BotMember.of(guild.getSelfMember()),
                    guild.getChannels().stream().map(Channel::of).toList(),
                    guild.getRoles().stream()
                            .filter(role -> !role.isPublicRole())
                            .sorted(Comparator.comparingInt(Role::getPosition).reversed())
                            .map(RoleView::of)
                            .toList());
        }
    }

    public record BotMember(int highestRolePosition, List<Permission> permissions) {

        static BotMember of(Member self) {
            int position = self.getRoles().isEmpty() ? 0 : self.getRoles().getFirst().getPosition();
            return new BotMember(position, List.copyOf(self.getPermissions()));
        }
    }

    public record Channel(String id, String name, String type, String parentId, int position) {

        static Channel of(GuildChannel channel) {
            String parentId = channel instanceof ICategorizableChannel categorizable ? categorizable.getParentCategoryId() : null;
            int position = channel instanceof IPositionableChannel positionable ? positionable.getPosition() : 0;
            return new Channel(channel.getId(), channel.getName(), channel.getType().name().toLowerCase(), parentId, position);
        }
    }

    public record RoleView(String id, String name, int color, int position, boolean managed) {

        static RoleView of(Role role) {
            var color = role.getColors().getPrimaryRaw();
            return new RoleView(role.getId(), role.getName(), color, role.getPosition(), role.isManaged());
        }
    }

    public record EmojiView(String id, String name, boolean animated) {

        public static EmojiView of(RichCustomEmoji emoji) {
            return new EmojiView(emoji.getId(), emoji.getName(), emoji.isAnimated());
        }
    }
}
