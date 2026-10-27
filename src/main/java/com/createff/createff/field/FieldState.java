package com.createff.createff.field;

public enum FieldState {
    /** Powered and protecting its area. */
    ONLINE,
    /** Not enough RPM; the area is unclaimed. */
    OFFLINE,
    /** Overloaded and cooling down; the area is unclaimed. */
    OVERHEATED;

    public boolean protects() {
        return this == ONLINE;
    }
}
