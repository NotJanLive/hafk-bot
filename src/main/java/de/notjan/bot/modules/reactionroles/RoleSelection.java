package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Mode;

import java.util.HashSet;
import java.util.Set;

final class RoleSelection {

    record Change(Set<Long> add, Set<Long> remove) {

        static final Change NONE = new Change(Set.of(), Set.of());

        Change {
            add = Set.copyOf(add);
            remove = Set.copyOf(remove);
        }

        boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    private RoleSelection() {
    }

    static Change toggle(Mode mode, Set<Long> panelRoles, Set<Long> memberRoles, long roleId) {
        if (memberRoles.contains(roleId)) {
            return mode == Mode.VERIFY ? Change.NONE : new Change(Set.of(), Set.of(roleId));
        }
        return add(mode, panelRoles, memberRoles, roleId);
    }

    static Change select(Mode mode, Set<Long> panelRoles, Set<Long> memberRoles, Set<Long> selected) {
        Set<Long> add = new HashSet<>(selected);
        add.removeAll(memberRoles);
        if (mode == Mode.VERIFY) {
            return new Change(add, Set.of());
        }
        Set<Long> remove = new HashSet<>(panelRoles);
        remove.retainAll(memberRoles);
        remove.removeAll(selected);
        return new Change(add, remove);
    }

    static Change reactionAdded(Mode mode, Set<Long> panelRoles, Set<Long> memberRoles, long roleId) {
        return memberRoles.contains(roleId) ? Change.NONE : add(mode, panelRoles, memberRoles, roleId);
    }

    static Change reactionRemoved(Mode mode, Set<Long> memberRoles, long roleId) {
        if (mode == Mode.VERIFY || !memberRoles.contains(roleId)) {
            return Change.NONE;
        }
        return new Change(Set.of(), Set.of(roleId));
    }

    private static Change add(Mode mode, Set<Long> panelRoles, Set<Long> memberRoles, long roleId) {
        if (mode != Mode.UNIQUE) {
            return new Change(Set.of(roleId), Set.of());
        }
        Set<Long> remove = new HashSet<>(panelRoles);
        remove.retainAll(memberRoles);
        remove.remove(roleId);
        return new Change(Set.of(roleId), remove);
    }
}
