package dev.sixik.snoisium.fabric;

import dev.sixik.snoisium.SNoisium;
import net.fabricmc.api.ModInitializer;

public final class SNoisiumFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        SNoisium.init();
    }
}
