package me.oredev.escadona;

import com.mojang.logging.LogUtils;
import me.oredev.escadona.config.EscadonaConfig;
import me.oredev.escadona.event.ServerEventHandler;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Escadona.MOD_ID)
public final class Escadona {
    public static final String MOD_ID = "escadona";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Escadona(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, EscadonaConfig.SPEC);
        NeoForge.EVENT_BUS.register(new ServerEventHandler(new ServerRuntimeRegistry()));
        LOGGER.info("Escadona {} initialized", modContainer.getModInfo().getVersion());
    }
}
