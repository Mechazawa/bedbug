package at.ioexception.mcaddon.client.integration;

//? if >=26 {
import at.ioexception.mcaddon.client.render.ColourGrading;
import at.ioexception.mcaddon.client.render.PaperDoll;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Adds the paper doll and colour grading options to Sodium's video settings, which replace Java's screen. */
public final class SodiumIntegration implements ConfigEntryPoint {
    public static final Identifier PAPER_DOLL = Identifier.fromNamespaceAndPath("mcaddon", "paper_doll");
    public static final Identifier COLOUR_GRADING = Identifier.fromNamespaceAndPath("mcaddon", "colour_grading");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("options.mcaddon.title"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(option(builder, PAPER_DOLL, "options.mcaddon.paperDoll", PaperDoll.OPTION, true))
                    .addOption(option(builder, COLOUR_GRADING, "options.mcaddon.colourGrading",
                        ColourGrading.OPTION, false))));
    }

    private static BooleanOptionBuilder option(ConfigBuilder builder, Identifier id, String key,
        OptionInstance<Boolean> option, boolean defaultValue) {
        return builder.createBooleanOption(id)
            .setName(Component.translatable(key))
            .setTooltip(Component.translatable(key + ".tooltip"))
            .setDefaultValue(defaultValue)
            .setBinding(option::set, option::get)
            .setStorageHandler(() -> Minecraft.getInstance().options.save());
    }
}
//?}
