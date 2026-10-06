package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Mode;
import de.notjan.bot.modules.reactionroles.RoleSelection.Change;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleSelectionTest {

    private static final Set<Long> PANEL = Set.of(1L, 2L, 3L);

    @Test
    void normalToggleAddsAndRemoves() {
        assertEquals(new Change(Set.of(1L), Set.of()), RoleSelection.toggle(Mode.NORMAL, PANEL, Set.of(9L), 1L));
        assertEquals(new Change(Set.of(), Set.of(1L)), RoleSelection.toggle(Mode.NORMAL, PANEL, Set.of(1L, 9L), 1L));
    }

    @Test
    void uniqueToggleReplacesOtherPanelRoles() {
        assertEquals(new Change(Set.of(2L), Set.of(1L, 3L)), RoleSelection.toggle(Mode.UNIQUE, PANEL, Set.of(1L, 3L, 9L), 2L));
    }

    @Test
    void verifyNeverRemoves() {
        assertEquals(Change.NONE, RoleSelection.toggle(Mode.VERIFY, PANEL, Set.of(1L), 1L));
        assertEquals(Change.NONE, RoleSelection.reactionRemoved(Mode.VERIFY, Set.of(1L), 1L));
        assertEquals(new Change(Set.of(2L), Set.of()), RoleSelection.select(Mode.VERIFY, PANEL, Set.of(1L), Set.of(2L)));
    }

    @Test
    void selectMakesSelectionTheNewSetOfPanelRoles() {
        Change change = RoleSelection.select(Mode.NORMAL, PANEL, Set.of(1L, 2L, 9L), Set.of(2L, 3L));

        assertEquals(Set.of(3L), change.add());
        assertEquals(Set.of(1L), change.remove());
    }

    @Test
    void reactionAddedIgnoresRolesTheMemberAlreadyHas() {
        assertEquals(Change.NONE, RoleSelection.reactionAdded(Mode.NORMAL, PANEL, Set.of(1L), 1L));
    }

    @Test
    void reactionRemovedOnlyRemovesOwnedRoles() {
        assertEquals(Change.NONE, RoleSelection.reactionRemoved(Mode.NORMAL, Set.of(), 1L));
        assertEquals(new Change(Set.of(), Set.of(1L)), RoleSelection.reactionRemoved(Mode.UNIQUE, Set.of(1L), 1L));
    }
}
