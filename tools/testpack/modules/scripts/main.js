// Imports every module the mod provides and calls one thing from each.
//
// A JavaScript module is linked before any of it runs, so a specifier that does not resolve, or a
// named export missing from one that does, costs this pack every line of its script. Both are
// caught by the imports below: reaching the first MODULEPROBE line means every module linked, and a
// line per module means each of them answered.

import { EntityRemoveBeforeEvent, system, world } from "@minecraft/server";
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

system.run(() => {
    try {
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
