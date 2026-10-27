package com.createff.createff;

import org.slf4j.Logger;

import com.createff.createff.registry.Registration;
import com.mojang.logging.LogUtils;
import com.simibubi.create.api.stress.BlockStressValues;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(CreateFF.MODID)
public class CreateFF {
    public static final String MODID = "createff";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateFF(IEventBus modEventBus, ModContainer modContainer) {
        Registration.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Base impact per RPM; the block entity scales it with the protected volume.
            BlockStressValues.IMPACTS.register(Registration.FIELD_PROJECTOR.get(), () -> Config.STRESS_BASE_IMPACT.get());
        });
    }
}
