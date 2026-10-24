package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.WorldAddons;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Create World plays the add-ons chosen for the new world, and the installed ones again once it is
 * left without a world.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin implements WorldAddons.DataPacks {
    @Invoker("getDataPackSelectionSettings")
    abstract Pair<Path, PackRepository> mcaddon$dataPackSelection(WorldDataConfiguration configuration);

    @Invoker("tryApplyNewDataPacks")
    abstract void mcaddon$tryApplyNewDataPacks(PackRepository repository, boolean isDataPackScreen,
                                               Consumer<WorldDataConfiguration> onAbort);

    @Override
    public void mcaddon$reapply() {
        CreateWorldScreen screen = (CreateWorldScreen) (Object) this;
        Pair<Path, PackRepository> selection =
            mcaddon$dataPackSelection(screen.getUiState().getSettings().dataConfiguration());
        if (selection == null) return;
        PackRepository repository = selection.getSecond();
        repository.reload();
        mcaddon$tryApplyNewDataPacks(repository, false, ignored -> Platform.setScreen(screen));
    }

    /** Re-Create has just opened the source world's list, which the new world takes. */
    @Inject(method = "createFromExisting", at = @At("RETURN"))
    private static void mcaddon$copyList(CallbackInfoReturnable<CreateWorldScreen> info) {
        WorldAddons.pending(ActiveAddons.world());
    }

    @Inject(method = "popScreen", at = @At("HEAD"))
    private void mcaddon$restore(CallbackInfo info) {
        WorldAddons.cancel();
    }
}
