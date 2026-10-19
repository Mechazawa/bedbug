package at.ioexception.mcaddon.server;

import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
//? if <26.3 {
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
//?}
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;

import java.util.List;
import java.util.Map;

/**
 * Bedrock's packet ids, as Mojang publishes them in its protocol documentation, against the Java
 * packets a client sends with the same message. A Bedrock id missing here has no message a Java
 * client sends: a skin upload, a form answer carried by this mod's own channel, a resource pack
 * chunk Java downloads over HTTP, and everything a server sends rather than receives.
 */
final class BedrockPacketIds {
    //? if >=1.21 {
    private static final Class<?> CLIENT_INFORMATION =
        net.minecraft.network.protocol.common.ServerboundClientInformationPacket.class;
    //?} else {
    /*private static final Class<?> CLIENT_INFORMATION =
        net.minecraft.network.protocol.game.ServerboundClientInformationPacket.class;
    *///?}

    static final Map<Integer, List<Class<?>>> JAVA_CARRIERS = Map.ofEntries(
        Map.entry(1, List.of(ServerboundHelloPacket.class)),
        Map.entry(4, List.of(ServerboundKeyPacket.class)),
        //? if >=1.21 {
        Map.entry(8, List.of(net.minecraft.network.protocol.common.ServerboundResourcePackPacket.class)),
        //?} else {
        /*Map.entry(8, List.of(net.minecraft.network.protocol.game.ServerboundResourcePackPacket.class)),
        *///?}
        Map.entry(9, List.of(ServerboundChatPacket.class)),
        Map.entry(19, List.of(ServerboundMovePlayerPacket.class)),
        //? if >=26 {
        Map.entry(30, List.of(ServerboundUseItemOnPacket.class, ServerboundUseItemPacket.class,
            ServerboundInteractPacket.class, net.minecraft.network.protocol.game.ServerboundAttackPacket.class)),
        //?} else {
        /*Map.entry(30, List.of(ServerboundUseItemOnPacket.class, ServerboundUseItemPacket.class,
            ServerboundInteractPacket.class)),
        *///?}
        Map.entry(31, List.of(ServerboundSetCarriedItemPacket.class)),
        //? if >=26 {
        Map.entry(34, List.of(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket.class)),
        Map.entry(35, List.of(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket.class)),
        //?} else {
        /*Map.entry(34, List.of(net.minecraft.network.protocol.game.ServerboundPickItemPacket.class)),
        *///?}
        Map.entry(36, List.of(ServerboundPlayerActionPacket.class, ServerboundPlayerCommandPacket.class)),
        //? if >=26.3 {
        /*Map.entry(44, List.of(net.minecraft.network.protocol.game.ServerboundPunchPacket.class)),
        *///?} else {
        Map.entry(44, List.of(ServerboundSwingPacket.class)),
        //?}
        Map.entry(45, List.of(ServerboundClientCommandPacket.class)),
        Map.entry(47, List.of(ServerboundContainerClosePacket.class)),
        Map.entry(48, List.of(ServerboundSetCarriedItemPacket.class)),
        Map.entry(69, List.of(CLIENT_INFORMATION)),
        //? if >=1.21 {
        Map.entry(77, List.of(ServerboundChatCommandPacket.class,
            net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket.class)),
        //?} else {
        /*Map.entry(77, List.of(ServerboundChatCommandPacket.class)),
        *///?}
        Map.entry(78, List.of(ServerboundSetCommandBlockPacket.class, ServerboundSetCommandMinecartPacket.class)),
        Map.entry(90, List.of(ServerboundSetStructureBlockPacket.class)),
        Map.entry(97, List.of(ServerboundEditBookPacket.class)),
        //? if >=26 {
        Map.entry(113, List.of(net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket.class)),
        //?}
        //? if >=1.21 {
        Map.entry(115, List.of(net.minecraft.network.protocol.common.ServerboundKeepAlivePacket.class,
            net.minecraft.network.protocol.common.ServerboundPongPacket.class)),
        //?} else {
        /*Map.entry(115, List.of(net.minecraft.network.protocol.game.ServerboundKeepAlivePacket.class,
            net.minecraft.network.protocol.game.ServerboundPongPacket.class)),
        *///?}
        Map.entry(125, List.of(ServerboundContainerButtonClickPacket.class)),
        //? if >=26 {
        Map.entry(140, List.of(ServerboundChangeDifficultyPacket.class, ServerboundLockDifficultyPacket.class,
            net.minecraft.network.protocol.game.ServerboundSetGameRulePacket.class)),
        Map.entry(144, List.of(ServerboundMovePlayerPacket.class, ServerboundPlayerInputPacket.class,
            net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.class)),
        //?} else {
        /*Map.entry(140, List.of(ServerboundChangeDifficultyPacket.class, ServerboundLockDifficultyPacket.class)),
        Map.entry(144, List.of(ServerboundMovePlayerPacket.class, ServerboundPlayerInputPacket.class)),
        *///?}
        Map.entry(147, List.of(ServerboundContainerClickPacket.class, ServerboundPlaceRecipePacket.class,
            ServerboundSetCreativeModeSlotPacket.class)),
        Map.entry(184, List.of(ServerboundPlayerAbilitiesPacket.class)),
        Map.entry(193, List.of(ClientIntentionPacket.class)),
        //? if >=1.21 {
        Map.entry(306, List.of(net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket.class)),
        //?}
        Map.entry(307, List.of(ServerboundRecipeBookChangeSettingsPacket.class)),
        Map.entry(323, List.of(CLIENT_INFORMATION)),
        Map.entry(351, List.of(ServerboundRecipeBookChangeSettingsPacket.class)),
        Map.entry(354, List.of(ServerboundContainerButtonClickPacket.class)),
        Map.entry(358, List.of(ServerboundContainerClickPacket.class)),
        Map.entry(361, List.of(ServerboundChatPacket.class)));

    private BedrockPacketIds() {
    }
}
