package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Tells a pack's script that a player opened a container, and that they closed it again.
 *
 * A menu provider is the container itself wherever Java has one to hand: a chest is its block
 * entity, and a villager, a chest minecart and a chest boat are the entity. A mount carries its
 * inventory through a method of its own, whose class moved package in 26, so that one is injected
 * twice rather than once.
 *
 * The game mode is here as well, because a pack refusing the change has to stop it before the
 * player is told about it.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "openMenu", at = @At("RETURN"))
    private void mcaddon$opened(MenuProvider provider, CallbackInfoReturnable<OptionalInt> info) {
        if (info.getReturnValue().isPresent()) {
            WorldEvents.menuOpened((ServerPlayer) (Object) this, provider);
        }
    }

    //? if >=26 {
    @Inject(method = "openHorseInventory", at = @At("TAIL"))
    private void mcaddon$openedMount(net.minecraft.world.entity.animal.equine.AbstractHorse mount,
        Container container, CallbackInfo info) {
        WorldEvents.mountOpened((ServerPlayer) (Object) this, mount);
    }
    //?} else {
    /*@Inject(method = "openHorseInventory", at = @At("TAIL"))
    private void mcaddon$openedMount(net.minecraft.world.entity.animal.horse.AbstractHorse mount,
        Container container, CallbackInfo info) {
        WorldEvents.mountOpened((ServerPlayer) (Object) this, mount);
    }
    *///?}

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void mcaddon$closed(CallbackInfo info) {
        WorldEvents.containerClosed((ServerPlayer) (Object) this);
    }

    @Inject(method = "setGameMode", at = @At("HEAD"), cancellable = true)
    private void mcaddon$changingGameMode(GameType mode, CallbackInfoReturnable<Boolean> info) {
        if (WorldEvents.gameModeRefused((ServerPlayer) (Object) this, mode)) info.setReturnValue(false);
    }

    /**
     * The ceilings a pack put on the food and experience a player may hold.
     *
     * Java keeps its own as literals inside `FoodData` and keeps none at all for the level, so there
     * is nothing to hand a lower one to and the value has to be held under it where it is next
     * looked at. A world whose packs write no player definition reads one field and stops.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcaddon$capped(CallbackInfo info) {
        PlayerDefinition declared = PlayerDefinition.declared();
        if (declared != null) declared.capped((ServerPlayer) (Object) this);
    }
}
