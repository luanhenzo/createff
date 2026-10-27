package com.forcefields.forcefields.client;

import com.forcefields.forcefields.ForceFields;
import com.forcefields.forcefields.registry.Registration;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = ForceFields.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.FIELD_PROJECTOR_MENU.get(), FieldScreen::new);
    }

    private ClientSetup() {
    }
}
