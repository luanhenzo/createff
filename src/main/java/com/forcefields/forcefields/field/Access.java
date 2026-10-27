package com.forcefields.forcefields.field;

/** Who may do something in a field. */
public enum Access {
    /** The owner and the admins. */
    OWNER,
    /** The owner, the admins and trusted players. */
    TRUSTED,
    /** Everyone. */
    EVERYONE;

    public boolean allows(Role role) {
        return switch (this) {
            case OWNER -> role == Role.OWNER || role == Role.ADMIN;
            case TRUSTED -> role != Role.OTHER;
            case EVERYONE -> true;
        };
    }
}
