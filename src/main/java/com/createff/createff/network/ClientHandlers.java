package com.createff.createff.network;

import com.createff.createff.client.FieldHeat;
import com.createff.createff.client.FieldRipples;
import com.createff.createff.client.FieldScreen;

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
