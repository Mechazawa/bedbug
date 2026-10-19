package at.ioexception.mcaddon;

import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.pack.PackDiscovery;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.registry.AddonBrewing;
import at.ioexception.mcaddon.registry.AddonCreativeTab;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonSpawns;
import at.ioexception.mcaddon.registry.AddonWorldgen;
import at.ioexception.mcaddon.resource.AddonPacks;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class McAddon implements ModInitializer {
    public static final String MOD_ID = "mcaddon";

    /**
     * This mod's own version, which is part of what says a converted pack is out of date.
     *
     * What the conversion produces is a function of the pack and of the code that read it, so a
     * build that converts differently has to be told apart from the one before it. Without this a
     * pack converted by an older build is reused whole, and a fix to the conversion reaches nobody
     * who already had that pack installed.
     */
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
        .getModContainer(MOD_ID)
        .map(container -> container.getMetadata().getVersion().getFriendlyString())
        .orElse("dev");
    public static final Logger LOGGER = LoggerFactory.getLogger("Bedrock Add-ons");

    private static PackSet packs = new PackSet(List.of(), List.of());

    public static Identifier id(String path) {
        return Platform.id(MOD_ID, path);
    }

    /** The packs found at startup. Empty until the mod has initialised. */
    public static PackSet packs() {
        return packs;
    }

    /**
     * Scanning happens here, not lazily, because sound events, items, blocks and entity types all
     * have to be registered before the registries freeze, and every one of them comes from a pack.
     */
    @Override
    public void onInitialize() {
        // Before the packs are read, because a converted loot table names this condition type and
        // this function type, and the game looks both names up as it parses the table.
        at.ioexception.mcaddon.bedrock.behaviour.BedrockCondition.register();
        at.ioexception.mcaddon.bedrock.behaviour.BedrockFunction.register();

        packs = PackDiscovery.scan();
        at.ioexception.mcaddon.pack.PackSettings.read(packs);
        packs.report().forEach(LOGGER::info);

        ActiveAddons.install(AddonPacks.convert(packs));
        // After the items, because a brewing recipe naming a pack's own bottle has to find it.
        AddonBrewing.register();
        // The worldgen before the spawn rules, because a spawn rule may select on a tag only a
        // pack's own biome carries.
        AddonWorldgen.register(ActiveAddons.active().converted());
        AddonSpawns.register(packs);
        // Once the add-ons' own entities, items and blocks are registered, because a function line
        // naming one of them translates only once the name can be found.
        AddonPacks.reportFunctionLines();
        // Logged after the spawn rules are read and the function lines translated, because what
        // either drops belongs in the same register as the components an entity declares.
        AddonEntities.report().forEach(LOGGER::info);
        AddonCreativeTab.register();
        Channels.register();
        at.ioexception.mcaddon.network.SyncChannels.register();
        at.ioexception.mcaddon.server.AddonSyncTask.register();
        at.ioexception.mcaddon.server.AddonSyncLogin.register();
        at.ioexception.mcaddon.sync.ServedArchives.register();
        at.ioexception.mcaddon.server.PacketLimits.register();
        ServerHooks.register();
        at.ioexception.mcaddon.structure.PackStructures.register();
    }
}
