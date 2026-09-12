// Imports every module the mod provides and calls one thing from each.
//
// A JavaScript module is linked before any of it runs, so a specifier that does not resolve, or a
// named export that is missing from one that does, ends this whole script rather than the line that
// asked for it. Both are caught by the imports below: reaching the first MODULEPROBE line means
// every module linked, and a line per module means each of them answered.

import { system, world } from "@minecraft/server";
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

system.run(() => {
    try {
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

        const registration = register("probe", "one", () => {}).tag(Tags.suiteDefault).maxTicks(20);
        report("gametest.registration", registration.constructor.name);
    } catch (error) {
        report("threw", error && error.stack ? error.stack : String(error));
    }
});
