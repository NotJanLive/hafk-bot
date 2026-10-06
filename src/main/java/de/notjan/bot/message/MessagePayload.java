package de.notjan.bot.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MessagePayload(String content, List<Embed> embeds) {

    public MessagePayload {
        embeds = embeds == null ? List.of() : List.copyOf(embeds);
    }

    public Embed firstEmbed() {
        return embeds.isEmpty() ? Embed.EMPTY : embeds.getFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Embed(
            String title,
            String description,
            String url,
            Integer color,
            String timestamp,
            Author author,
            Footer footer,
            Media thumbnail,
            Media image,
            List<Field> fields
    ) {

        public static final Embed EMPTY = new Embed(null, null, null, null, null, null, null, null, null, List.of());

        public Embed {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Author(String name, String url, String iconUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Footer(String text, String iconUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Media(String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String name, String value, boolean inline) {
    }
}
