package com.createff.createff.field;

/** What a player is to a field. */
public enum Role {
    /** Placed the core. Can do everything, including break the core. */
    OWNER,
    /** Can do everything the owner can except break the core. */
    ADMIN,
    /** Can use the area as the rules allow, but cannot change anything about the field. */
    TRUSTED,
    OTHER
}
