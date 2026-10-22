package de.notjan.bot.modules.tickets;

import de.notjan.bot.modules.tickets.Ticket.Answer;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.FieldOption;
import de.notjan.bot.modules.tickets.TicketConfig.FormField;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.components.attachmentupload.AttachmentUpload;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.checkboxgroup.CheckboxGroup;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.label.LabelChildComponent;
import net.dv8tion.jda.api.components.radiogroup.RadioGroup;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

final class TicketForms {

    static final int FIELDS_PER_PAGE = 5;
    static final int MAX_FIELDS = 25;
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    record Result(List<Answer> answers, List<Message.Attachment> files) {
    }

    private record Session(List<Answer> answers, List<Message.Attachment> files, Instant updatedAt) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static int pages(Category category) {
        return (category.fields().size() + FIELDS_PER_PAGE - 1) / FIELDS_PER_PAGE;
    }

    static Modal modal(Category category, int page) {
        int pages = pages(category);
        String base = category.formTitle() == null || category.formTitle().isBlank() ? category.name() : category.formTitle();
        String title = pages > 1 ? base + " (" + (page + 1) + "/" + pages + ")" : base;
        List<FormField> fields = fields(category, page);
        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            FormField field = fields.get(i);
            labels.add(Label.of(TicketRenderer.truncate(field.label(), 45), blankToNull(field.description()), component(field, "f" + i)));
        }
        return Modal.create(TicketRenderer.id("form", category.id(), page), TicketRenderer.truncate(title, 45))
                .addComponents(labels)
                .build();
    }

    Result submit(long userId, Category category, int page, ModalInteractionEvent event) {
        List<FormField> fields = fields(category, page);
        List<Answer> answers = new ArrayList<>();
        List<Message.Attachment> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            FormField field = fields.get(i);
            ModalMapping mapping = event.getValue("f" + i);
            String answer = mapping == null ? null : answer(field, mapping, files);
            if (field.type() == TicketConfig.FieldType.CHECKBOX && field.required() && (mapping == null || !mapping.getAsBoolean())) {
                errors.add("„" + field.label() + "“ muss bestätigt werden.");
            }
            answers.add(new Answer(field.label(), answer));
        }
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
        String key = key(userId, category.id());
        cleanup();
        Session previous = page == 0 ? null : sessions.get(key);
        if (page > 0 && previous == null) {
            throw new UserFacingException("Das Formular ist abgelaufen. Bitte starte es noch einmal über das Ticket-Panel.");
        }
        List<Answer> allAnswers = new ArrayList<>(previous == null ? List.of() : previous.answers());
        allAnswers.addAll(answers);
        List<Message.Attachment> allFiles = new ArrayList<>(previous == null ? List.of() : previous.files());
        allFiles.addAll(files);
        if (page + 1 < pages(category)) {
            sessions.put(key, new Session(allAnswers, allFiles, Instant.now()));
            return null;
        }
        sessions.remove(key);
        return new Result(allAnswers, allFiles);
    }

    boolean hasSession(long userId, long categoryId) {
        cleanup();
        return sessions.containsKey(key(userId, categoryId));
    }

    private static List<FormField> fields(Category category, int page) {
        int from = page * FIELDS_PER_PAGE;
        if (from >= category.fields().size()) {
            throw new UserFacingException("Diese Formularseite gibt es nicht mehr.");
        }
        return category.fields().subList(from, Math.min(category.fields().size(), from + FIELDS_PER_PAGE));
    }

    private static LabelChildComponent component(FormField field, String id) {
        int optionCount = field.options().size();
        return switch (field.type()) {
            case SHORT, PARAGRAPH -> {
                TextInput.Builder input = TextInput.create(id, field.type() == TicketConfig.FieldType.SHORT
                                ? TextInputStyle.SHORT : TextInputStyle.PARAGRAPH)
                        .setRequired(field.required());
                if (field.minLength() != null && field.minLength() > 0) {
                    input.setMinLength(field.minLength());
                }
                if (field.maxLength() != null && field.maxLength() > 0) {
                    input.setMaxLength(field.maxLength());
                }
                if (field.placeholder() != null && !field.placeholder().isBlank()) {
                    input.setPlaceholder(field.placeholder());
                }
                yield input.build();
            }
            case SELECT -> {
                StringSelectMenu.Builder menu = StringSelectMenu.create(id).setRequired(field.required());
                for (int i = 0; i < optionCount; i++) {
                    FieldOption option = field.options().get(i);
                    menu.addOption(TicketRenderer.truncate(option.label(), 100), String.valueOf(i),
                            blankToNull(option.description()), TicketRenderer.emoji(option.emoji()));
                }
                int[] range = range(field, optionCount);
                menu.setRequiredRange(range[0], range[1]);
                if (field.placeholder() != null && !field.placeholder().isBlank()) {
                    menu.setPlaceholder(field.placeholder());
                }
                yield menu.build();
            }
            case RADIO -> {
                RadioGroup.Builder group = RadioGroup.create(id).setRequired(field.required());
                for (int i = 0; i < optionCount; i++) {
                    FieldOption option = field.options().get(i);
                    group.addOption(TicketRenderer.truncate(option.label(), 100), String.valueOf(i), blankToNull(option.description()));
                }
                yield group.build();
            }
            case CHECKBOXES -> {
                CheckboxGroup.Builder group = CheckboxGroup.create(id).setRequired(field.required());
                for (int i = 0; i < optionCount; i++) {
                    FieldOption option = field.options().get(i);
                    group.addOption(TicketRenderer.truncate(option.label(), 100), String.valueOf(i), blankToNull(option.description()));
                }
                int[] range = range(field, optionCount);
                group.setRequiredRange(field.required() ? Math.max(1, range[0]) : range[0], range[1]);
                yield group.build();
            }
            case CHECKBOX -> Checkbox.of(id);
            case USER, ROLE, CHANNEL -> {
                SelectTarget target = field.type() == TicketConfig.FieldType.USER ? SelectTarget.USER
                        : field.type() == TicketConfig.FieldType.ROLE ? SelectTarget.ROLE : SelectTarget.CHANNEL;
                EntitySelectMenu.Builder menu = EntitySelectMenu.create(id, target).setRequired(field.required());
                int max = field.maxValues() == null ? 1 : Math.max(1, Math.min(25, field.maxValues()));
                int min = field.minValues() == null ? (field.required() ? 1 : 0) : Math.max(0, Math.min(max, field.minValues()));
                menu.setRequiredRange(min, max);
                if (field.placeholder() != null && !field.placeholder().isBlank()) {
                    menu.setPlaceholder(field.placeholder());
                }
                yield menu.build();
            }
            case FILE -> {
                int max = field.maxValues() == null ? 1 : Math.max(1, Math.min(AttachmentUpload.MAX_UPLOADS, field.maxValues()));
                int min = field.minValues() == null ? (field.required() ? 1 : 0) : Math.max(0, Math.min(max, field.minValues()));
                yield AttachmentUpload.create(id).setRequired(field.required()).setRequiredRange(min, max).build();
            }
        };
    }

    private static int[] range(FormField field, int optionCount) {
        int max = field.maxValues() == null ? 1 : Math.max(1, Math.min(optionCount, field.maxValues()));
        int min = field.minValues() == null ? (field.required() ? 1 : 0) : Math.max(0, Math.min(max, field.minValues()));
        return new int[]{min, max};
    }

    private static String answer(FormField field, ModalMapping mapping, List<Message.Attachment> files) {
        return switch (field.type()) {
            case SHORT, PARAGRAPH -> blankToNull(mapping.getAsString());
            case SELECT, RADIO, CHECKBOXES -> {
                List<String> values = field.type() == TicketConfig.FieldType.RADIO
                        ? (mapping.getAsOptionalString() == null ? List.of() : List.of(mapping.getAsOptionalString()))
                        : mapping.getAsStringList();
                String joined = values.stream().map(value -> optionLabel(field, value)).collect(Collectors.joining(", "));
                yield joined.isBlank() ? null : joined;
            }
            case CHECKBOX -> mapping.getAsBoolean() ? "✅ Ja" : "❌ Nein";
            case USER -> mentions(mapping.getAsMentions().getUsers());
            case ROLE -> mentions(mapping.getAsMentions().getRoles());
            case CHANNEL -> mentions(mapping.getAsMentions().getChannels());
            case FILE -> {
                List<Message.Attachment> attachments = mapping.getAsAttachmentList();
                files.addAll(attachments);
                yield attachments.isEmpty() ? null : attachments.stream().map(Message.Attachment::getFileName)
                        .map(name -> "📎 " + name).collect(Collectors.joining("\n"));
            }
        };
    }

    private static String optionLabel(FormField field, String value) {
        try {
            int index = Integer.parseInt(value);
            return index >= 0 && index < field.options().size() ? field.options().get(index).label() : value;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String mentions(List<? extends IMentionable> mentionables) {
        if (mentionables.isEmpty()) {
            return null;
        }
        return mentionables.stream().map(IMentionable::getAsMention).collect(Collectors.joining(", "));
    }

    private void cleanup() {
        Instant limit = Instant.now().minus(SESSION_TTL);
        sessions.values().removeIf(session -> session.updatedAt().isBefore(limit));
    }

    private static String key(long userId, long categoryId) {
        return userId + ":" + categoryId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
