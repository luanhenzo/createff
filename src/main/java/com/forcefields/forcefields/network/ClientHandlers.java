package com.forcefields.forcefields.network;

import com.forcefields.forcefields.client.FieldHeat;
import com.forcefields.forcefields.client.FieldRipples;
import com.forcefields.forcefields.client.FieldScreen;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Kept apart from {@link FieldNetwork} so the dedicated server never loads the screen class. */
final class ClientHandlers {
    static void message(FieldMessage payload, IPayloadContext context) {
        FieldScreen.showMessage(payload.ok(), payload.key(), payload.args());
    }

    static void hit(FieldHit payload, IPayloadContext context) {
        FieldHeat.record(payload.x(), payload.y(), payload.z(), payload.focus());
    }

    static void ripple(FieldRipple payload, IPayloadContext context) {
        FieldRipples.impact(payload.x(), payload.y(), payload.z(), payload.size(), payload.core());
    }

    private ClientHandlers() {
    }
}
