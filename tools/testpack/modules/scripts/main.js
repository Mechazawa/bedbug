// Imports every module the mod provides and calls one thing from each.
//
// A JavaScript module is linked before any of it runs, so a specifier that does not resolve, or a
// named export missing from one that does, costs this pack every line of its script. Both are
// caught by the imports below: reaching the first MODULEPROBE line means every module linked, and a
// line per module means each of them answered.

import {
    AimAssistRegistry,
    BlockContainerClosedAfterEventSignal,
    BlockContainerOpenedAfterEventSignal,
    BlockExplodeAfterEventSignal,
    EntityContainerClosedAfterEventSignal,
    EntityContainerOpenedAfterEventSignal,
    EntityRemoveBeforeEvent,
    EntityUpgradeAfterEventSignal,
    ExplosionAfterEventSignal,
    ExplosionBeforeEventSignal,
    GameRuleChangeAfterEventSignal,
    ItemStopUseOnAfterEventSignal,
    LocatorBar,
    PistonActivateAfterEventSignal,
    PlayerAimAssist,
    PlayerCancelBreakingBlockAfterEventSignal,
    PlayerEmoteAfterEventSignal,
    PlayerInputModeChangeAfterEventSignal,
    PlayerInputPermissionCategoryChangeAfterEventSignal,
    PlayerStartBreakingBlockAfterEventSignal,
    PlayerSwingStartAfterEventSignal,
    PlayerWaypoint,
    PressurePlatePopAfterEventSignal,
    PressurePlatePushAfterEventSignal,
    PrimitiveShape,
    PrimitiveShapesManager,
    TargetBlockHitAfterEventSignal,
    TextPrimitive,
    TripWireTripAfterEventSignal,
    Waypoint,
    system,
    world,
} from "@minecraft/server";
import { ActionFormData } from "@minecraft/server-ui";
import { NumberRange } from "@minecraft/common";
import { Vector3Builder, Vector3Utils, VECTOR3_UP, clampNumber } from "@minecraft/math";
import {
    MinecraftBlockTypes,
    MinecraftDimensionTypes,
    MinecraftEntityTypes,
    MinecraftItemTypes,
    MinecraftPotionLiquidTypes,
} from "@minecraft/vanilla-data";
import { Tags, register } from "@minecraft/server-gametest";

function report(label, value) {
    world.sendMessage("MODULEPROBE " + label + "=" + value);
}

// A before event is the one a pack writes back to. The class it arrives as declares every member
// the API publishes, and a member the server does not send reads as a report rather than as data,
// so writing `cancel` has to keep working against that. Cancelling is refused here: the point is
// that the write lands and the event is the class it says it is.
let reportedCancelling = false;

world.beforeEvents.entityRemove.subscribe(event => {
    const named = event instanceof EntityRemoveBeforeEvent;
    event.cancel = false;
    if (!reportedCancelling) {
        reportedCancelling = true;
        report("beforeEvent.isPublishedClass", named);
        report("beforeEvent.acceptsCancel", event.cancel === false);
    }
});

// A block or an item naming a custom component reaches the pack's own script at the moment the
// callback is named for. What is checked here is that the registry takes the registration: a pack
// that registers on startup and is told the registry is unimplemented loses the whole feature.
let registeredComponents = 0;

system.beforeEvents.startup.subscribe(event => {
    event.blockComponentRegistry.registerCustomComponent("probe:block", {
        onPlace() {
            report("customComponent.blockPlaced", true);
        },
    });
    event.itemComponentRegistry.registerCustomComponent("probe:item", {
        onUse() {
            report("customComponent.itemUsed", true);
        },
    });
    registeredComponents = 2;
});

// The events the mod raises out of the game itself rather than out of a pack's own data. Each one
// is asked for by name, so a signal the module stopped exporting ends this script at its import and
// a signal handed back as the wrong class shows up as a `false` here.
const RAISED_SIGNALS = {
    blockContainerClosed: BlockContainerClosedAfterEventSignal,
    blockContainerOpened: BlockContainerOpenedAfterEventSignal,
    blockExplode: BlockExplodeAfterEventSignal,
    entityContainerClosed: EntityContainerClosedAfterEventSignal,
    entityContainerOpened: EntityContainerOpenedAfterEventSignal,
    explosion: ExplosionAfterEventSignal,
    gameRuleChange: GameRuleChangeAfterEventSignal,
    itemStopUseOn: ItemStopUseOnAfterEventSignal,
    pistonActivate: PistonActivateAfterEventSignal,
    playerCancelBreakingBlock: PlayerCancelBreakingBlockAfterEventSignal,
    playerStartBreakingBlock: PlayerStartBreakingBlockAfterEventSignal,
    playerSwingStart: PlayerSwingStartAfterEventSignal,
    pressurePlatePop: PressurePlatePopAfterEventSignal,
    pressurePlatePush: PressurePlatePushAfterEventSignal,
    targetBlockHit: TargetBlockHitAfterEventSignal,
    tripWireTrip: TripWireTripAfterEventSignal,
};

// The published events Java has nothing to raise them from. A pack subscribing to one reaches a
// signal of the class Bedrock names, so keeping and testing it answers; nothing ever arrives on it.
const UNRAISED_SIGNALS = {
    entityUpgrade: EntityUpgradeAfterEventSignal,
    playerEmote: PlayerEmoteAfterEventSignal,
    playerInputModeChange: PlayerInputModeChangeAfterEventSignal,
    playerInputPermissionCategoryChange: PlayerInputPermissionCategoryChangeAfterEventSignal,
};

const arrived = new Set();

function watch(name) {
    world.afterEvents[name].subscribe(event => {
        if (arrived.has(name)) return;

        arrived.add(name);
        report("worldEvent." + name, event.constructor.name);
    });
}

for (const name of Object.keys(RAISED_SIGNALS)) watch(name);

world.beforeEvents.explosion.subscribe(event => {
    if (arrived.has("explosion.before")) return;

    arrived.add("explosion.before");
    event.cancel = false;
    report("worldEvent.explosion.before", event.getImpactedBlocks().length >= 0);
});

system.run(() => {
    try {
        const named = Object.entries(RAISED_SIGNALS)
            .filter(([name, signal]) => world.afterEvents[name] instanceof signal);
        report("worldEvents.signalsNamed", named.length + " of " + Object.keys(RAISED_SIGNALS).length);
        report("worldEvents.explosionBeforeNamed",
            world.beforeEvents.explosion instanceof ExplosionBeforeEventSignal);

        const unraised = Object.entries(UNRAISED_SIGNALS)
            .filter(([name, signal]) => world.afterEvents[name] instanceof signal);
        report("worldEvents.unraisedNamed",
            unraised.length + " of " + Object.keys(UNRAISED_SIGNALS).length);

        // What Bedrock draws on a client of its own. Each class links, each answers with the
        // class the published API names, and each member reports rather than answering wrongly.
        report("bedrockClient.aimAssist", world.getAimAssist() instanceof AimAssistRegistry);
        report("bedrockClient.packSettings", typeof world.getPackSettings());
        report("bedrockClient.primitiveShapes",
            world.primitiveShapesManager instanceof PrimitiveShapesManager);
        report("bedrockClient.textPrimitive", TextPrimitive.prototype instanceof PrimitiveShape);
        report("bedrockClient.playerWaypoint", PlayerWaypoint.prototype instanceof Waypoint);
        report("bedrockClient.locatorBarMember", typeof new LocatorBar().addWaypoint);
        report("bedrockClient.aimAssistMember", typeof new PlayerAimAssist().set);

        // Two of these the game can be made to raise with nobody playing: a rule written through
        // the module's own setter, and a charge set off beside a block put there to lose. Both wait
        // a few ticks, because a rule read for the first time on the tick it is written on is the
        // value the world started with rather than a change.
        system.runTimeout(() => {
            world.gameRules.doFireTick = !world.gameRules.doFireTick;
            const overworld = world.getDimension("overworld");
            overworld.runCommand("setblock 1 100 0 stone");
            overworld.runCommand("summon tnt 0 100 0");
        }, 10);

        report("customComponents.registered", registeredComponents);
        report("math.distance", Vector3Utils.distance({ x: 0, y: 0, z: 0 }, { x: 3, y: 4, z: 0 }));
        report("math.builder", new Vector3Builder(VECTOR3_UP).scale(3).toString());
        report("math.clampNumber", clampNumber(12, 0, 5));

        report("vanillaData.block", MinecraftBlockTypes.AcaciaButton);
        report("vanillaData.item", MinecraftItemTypes.DiamondSword);
        report("vanillaData.entity", MinecraftEntityTypes.Creeper);
        report("vanillaData.dimension", MinecraftDimensionTypes.Nether);
        report("vanillaData.liquid", MinecraftPotionLiquidTypes.Splash);
        report("vanillaData.blockCount", Object.keys(MinecraftBlockTypes).length);
        report("vanillaData.itemCount", Object.keys(MinecraftItemTypes).length);

        report("common.numberRange", new NumberRange(1, 4).max);
        report("serverUi.form", new ActionFormData().title("probe").button("ok").constructor.name);

        const registration = register("probe", "one", test => test.succeed())
            .tag(Tags.suiteDefault).maxTicks(20);
        report("gametest.registration", registration.constructor.name);
    } catch (error) {
        report("threw", error && error.stack ? error.stack : String(error));
    }
});
