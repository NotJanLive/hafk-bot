package de.notjan.bot.modules.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.notjan.bot.modules.tickets.TranscriptRepository.AssetKind;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.emoji.CustomEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.entities.sticker.Sticker;
import net.dv8tion.jda.api.entities.sticker.StickerItem;

import java.time.OffsetDateTime;

final class TranscriptSnapshot {

    private final ObjectMapper json;
    private final AssetStore assets;

    TranscriptSnapshot(ObjectMapper json, AssetStore assets) {
        this.json = json;
        this.assets = assets;
    }

    ObjectNode capture(Ticket ticket, Message message) {
        ObjectNode node = json.createObjectNode();
        node.put("id", message.getId());
        node.put("type", message.getType().name());
        node.put("system", message.getType().isSystem());
        node.set("author", author(ticket, message.getAuthor(), message.getMember()));
        node.put("content", message.getContentRaw());
        node.put("createdAt", message.getTimeCreated().toInstant().toString());
        OffsetDateTime edited = message.getTimeEdited();
        node.put("editedAt", edited == null ? null : edited.toInstant().toString());
        node.put("pinned", message.isPinned());
        if (message.getMessageReference() != null) {
            node.put("reference", message.getMessageReference().getMessageId());
        }

        ArrayNode embeds = node.putArray("embeds");
        for (MessageEmbed embed : message.getEmbeds()) {
            try {
                embeds.add(json.readTree(embed.toData().toString()));
            } catch (Exception e) {
                embeds.addObject().put("description", embed.getDescription());
            }
        }

        ArrayNode attachments = node.putArray("attachments");
        for (Message.Attachment attachment : message.getAttachments()) {
            ObjectNode item = attachments.addObject();
            Long assetId = assets.store(ticket, AssetKind.ATTACHMENT, attachment.getId(), attachment.getUrl(), attachment.getFileName(),
                    attachment.getContentType(), attachment.getSize());
            item.put("asset", assetId);
            item.put("name", attachment.getFileName());
            item.put("size", attachment.getSize());
            item.put("contentType", attachment.getContentType());
            if (attachment.getWidth() > 0) {
                item.put("width", attachment.getWidth());
                item.put("height", attachment.getHeight());
            }
            item.put("spoiler", attachment.isSpoiler());
            item.put("description", attachment.getDescription());
        }

        ArrayNode stickers = node.putArray("stickers");
        for (StickerItem sticker : message.getStickers()) {
            ObjectNode item = stickers.addObject();
            item.put("name", sticker.getName());
            item.put("format", sticker.getFormatType().name());
            if (sticker.getFormatType() != Sticker.StickerFormat.LOTTIE && sticker.getFormatType() != Sticker.StickerFormat.UNKNOWN) {
                item.put("asset", assets.store(ticket, AssetKind.STICKER, sticker.getId(), sticker.getIconUrl(),
                        sticker.getName() + "." + sticker.getFormatType().getExtension(), null, 0));
            }
        }

        ArrayNode reactions = node.putArray("reactions");
        for (MessageReaction reaction : message.getReactions()) {
            ObjectNode item = reactions.addObject();
            emoji(ticket, reaction.getEmoji(), item);
            item.put("count", reaction.getCount());
        }

        ObjectNode emojis = node.putObject("emojis");
        for (CustomEmoji emoji : message.getMentions().getCustomEmojis()) {
            emojis.put(emoji.getId(), customEmojiAsset(ticket, emoji));
        }

        ArrayNode components = node.putArray("components");
        for (Button button : message.getComponentTree().findAll(Button.class)) {
            ObjectNode item = components.addObject();
            item.put("label", button.getLabel());
            item.put("style", button.getStyle().name());
            item.put("url", button.getUrl());
            item.put("disabled", button.isDisabled());
            if (button.getEmoji() != null) {
                item.put("emoji", button.getEmoji().getFormatted());
            }
        }

        ObjectNode mentions = node.putObject("mentions");
        ObjectNode users = mentions.putObject("users");
        for (User user : message.getMentions().getUsers()) {
            Member member = message.isFromGuild() ? message.getGuild().getMemberById(user.getIdLong()) : null;
            users.put(user.getId(), member == null ? user.getEffectiveName() : member.getEffectiveName());
        }
        ObjectNode roles = mentions.putObject("roles");
        for (Role role : message.getMentions().getRoles()) {
            ObjectNode item = roles.putObject(role.getId());
            item.put("name", role.getName());
            int color = role.getColors().getPrimaryRaw();
            if (color != Role.DEFAULT_COLOR_RAW) {
                item.put("color", color);
            }
        }
        ObjectNode channels = mentions.putObject("channels");
        for (GuildChannel channel : message.getMentions().getChannels()) {
            channels.put(channel.getId(), channel.getName());
        }
        return node;
    }

    ObjectNode merge(JsonNode previous, ObjectNode next) {
        ArrayNode edits = next.putArray("edits");
        if (previous != null && previous.has("edits")) {
            previous.get("edits").forEach(edits::add);
        }
        if (previous != null && !previous.path("content").asText("").equals(next.path("content").asText(""))) {
            ObjectNode edit = edits.addObject();
            edit.put("content", previous.path("content").asText(""));
            edit.put("editedAt", next.path("editedAt").isNull() ? null : next.path("editedAt").asText());
        }
        return next;
    }

    private ObjectNode author(Ticket ticket, User user, Member member) {
        ObjectNode author = json.createObjectNode();
        author.put("id", user.getId());
        author.put("name", user.getName());
        author.put("displayName", member == null ? user.getEffectiveName() : member.getEffectiveName());
        author.put("bot", user.isBot());
        String avatarUrl = member == null ? user.getEffectiveAvatar().getUrl(128) : member.getEffectiveAvatar().getUrl(128);
        author.put("avatarUrl", avatarUrl);
        String avatarKey = user.getId() + ":" + (member != null && member.getAvatarId() != null
                ? member.getAvatarId()
                : user.getAvatarId() == null ? "default" : user.getAvatarId());
        author.put("avatar", assets.store(ticket, AssetKind.AVATAR, avatarKey, avatarUrl, "avatar-" + user.getId() + ".png", null, 0));
        if (member != null) {
            int color = member.getColors().getPrimaryRaw();
            if (color != Role.DEFAULT_COLOR_RAW) {
                author.put("color", color);
            }
        }
        return author;
    }

    private void emoji(Ticket ticket, EmojiUnion emoji, ObjectNode target) {
        if (emoji.getType() == Emoji.Type.CUSTOM) {
            CustomEmoji custom = emoji.asCustom();
            target.put("emojiId", custom.getId());
            target.put("emojiName", custom.getName());
            target.put("animated", custom.isAnimated());
            target.put("asset", customEmojiAsset(ticket, custom));
        } else {
            target.put("emoji", emoji.getName());
        }
    }

    private Long customEmojiAsset(Ticket ticket, CustomEmoji emoji) {
        return assets.store(ticket, AssetKind.EMOJI, emoji.getId(), emoji.getImageUrl(),
                emoji.getName() + (emoji.isAnimated() ? ".gif" : ".png"), emoji.isAnimated() ? "image/gif" : "image/png", 0);
    }
}
