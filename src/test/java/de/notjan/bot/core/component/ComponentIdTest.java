package de.notjan.bot.core.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentIdTest {

    @Test
    void roundTrips() {
        ComponentId id = ComponentId.of("tickets", "close", 123456789012345678L);

        assertEquals("tickets:close:123456789012345678", id.toString());
        assertEquals(id, ComponentId.parse(id.toString()));
        assertEquals(123456789012345678L, ComponentId.parse(id.toString()).longArg(0));
    }

    @Test
    void parsesWithoutArgs() {
        ComponentId id = ComponentId.parse("setup:finish");

        assertEquals("setup", id.namespace());
        assertEquals("finish", id.action());
        assertEquals(List.of(), id.args());
    }

    @Test
    void rejectsIdsWithoutAction() {
        assertThrows(IllegalArgumentException.class, () -> ComponentId.parse("legacy-button"));
    }

    @Test
    void rejectsIdsLongerThanDiscordAllows() {
        ComponentId id = ComponentId.of("ns", "action", "x".repeat(100));

        assertThrows(IllegalStateException.class, id::toString);
    }
}
