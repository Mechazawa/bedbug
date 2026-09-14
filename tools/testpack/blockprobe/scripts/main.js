import { world, system, BlockPermutation, BlockStates, BlockTypes } from "@minecraft/server";

// A custom component is how a pack gives one of its own blocks behaviour written in its script. The
// block below names this one, so placing that block is what proves the registration was taken and
// the callback reached the pack again.
system.beforeEvents.startup.subscribe(event => {
    event.blockComponentRegistry.registerCustomComponent("bp:probe", {
        onPlace(placed) {
            report("customComponentPlaced", placed?.block?.typeId ?? "no block");
        },
    });
});

function report(label, value) {
    world.sendMessage("BLOCKPROBE " + label + "=" + value);
}

system.runTimeout(() => {
    try {
        const overworld = world.getDimension("minecraft:overworld");
        overworld.runCommand("forceload add 0 0");
        system.runTimeout(() => probe(overworld), 5);
    } catch (error) {
        report("setupThrew", error && error.stack ? error.stack : String(error));
    }
}, 20);

function probe(overworld) {
    try {
        const stairs = BlockPermutation.resolve("oak_stairs", { facing: "east" });
        report("resolveType", stairs.type.id);
        report("resolveStates", JSON.stringify(stairs.getAllStates()));
        report("resolveState", stairs.getState("facing"));
        report("matchesNamespaced", stairs.matches("minecraft:oak_stairs", { facing: "east" }));
        report("matchesWrongState", stairs.matches("minecraft:oak_stairs", { facing: "west" }));
        report("withState", stairs.withState("half", "top").getState("half"));
        report("permutationItem", stairs.getItemStack(2)?.typeId);
        report("permutationTags", JSON.stringify(stairs.getTags().slice(0, 3)));
        report("permutationHasTag", stairs.hasTag("stairs"));
        report("canContainLiquid", stairs.canContainLiquid("Water"));
        report("isLiquidBlocking", stairs.isLiquidBlocking("Water"));
        report("destroyedByLiquid", BlockPermutation.resolve("minecraft:torch")
            .canBeDestroyedByLiquidSpread("Water"));
        report("spreadCausesSpawn", BlockPermutation.resolve("minecraft:torch")
            .liquidSpreadCausesSpawn("Water"));
        report("localizationKey", stairs.localizationKey);

        report("typeGet", BlockTypes.get("dirt")?.id);
        report("typeGetMissing", BlockTypes.get("mcaddon:nothing") ?? "undefined");
        report("typeCount", BlockTypes.getAll().length);
        report("stateGet", JSON.stringify(BlockStates.get("facing")?.validValues));
        report("stateCount", BlockStates.getAll().length);

        const at = { x: 1, y: 70, z: 1 };
        overworld.setBlockPermutation(at, stairs);
        const block = overworld.getBlock(at);
        report("placedType", block.typeId);
        report("placedFacing", block.permutation.getState("facing"));
        report("placedMatches", block.matches("minecraft:oak_stairs", { facing: "east" }));
        report("blockValid", block.isValid);
        report("blockAir", block.isAir);
        report("blockLiquid", block.isLiquid);
        report("blockSolid", block.isSolid);
        report("blockTags", JSON.stringify(block.getTags().slice(0, 2)));
        report("blockHasTag", block.hasTag("stairs"));
        report("blockItem", block.getItemStack()?.typeId);
        report("blockLight", block.getLightLevel());
        report("blockSkyLight", block.getSkyLightLevel());
        report("blockRedstone", block.getRedstonePower());
        report("blockFlowFrom", block.liquidCanFlowFromDirection("Water", "Up"));
        report("blockCentre", JSON.stringify(block.center()));

        block.setWaterlogged(true);
        report("waterlogged", block.isWaterlogged);
        block.setWaterlogged(false);

        block.setPermutation(BlockPermutation.resolve("minecraft:oak_stairs", { facing: "south" }));
        report("repermutedFacing", block.permutation.getState("facing"));

        report("above", block.above().typeId);
        report("below", block.below()?.typeId);
        report("north", block.north()?.location.z);
        report("offset", JSON.stringify(block.offset({ x: 2, y: 0, z: 0 })?.location));
        report("canPlace", block.above().canPlace(BlockPermutation.resolve("minecraft:stone")));

        block.above().setType("minecraft:oak_sign");
        const sign = block.above().getComponent("minecraft:sign");
        report("signComponent", sign?.typeId);
        sign.setText("line one\nline two");
        sign.setTextDyeColor("LightBlue");
        report("signText", JSON.stringify(sign.getText()));
        report("signColour", sign.getTextDyeColor());
        report("signRaw", JSON.stringify(sign.getRawText()));
        report("signBack", JSON.stringify(sign.getText("Back")));
        sign.setWaxed(true);
        report("signWaxed", sign.isWaxed);
        report("signOnStairs", block.getComponent("minecraft:sign") ?? "undefined");
        report("signHasComponent", block.above().hasComponent("minecraft:sign"));

        overworld.setBlockType({ x: 3, y: 70, z: 1 }, "minecraft:piston");
        const piston = overworld.getBlock({ x: 3, y: 70, z: 1 }).getComponent("minecraft:piston");
        report("pistonComponent", piston?.typeId);
        report("pistonState", piston?.state);
        report("pistonMoving", piston?.isMoving);
        report("pistonAttached", JSON.stringify(piston?.getAttachedBlocksLocations()));

        const colour = block.getComponent("minecraft:map_color");
        report("mapColour", JSON.stringify(colour?.color));
        report("mapTint", colour?.tintMethod);

        overworld.setBlockType({ x: 5, y: 70, z: 1 }, "minecraft:redstone_block");
        const producer = overworld.getBlock({ x: 5, y: 70, z: 1 })
            .getComponent("minecraft:redstone_producer");
        report("producerPower", producer?.power);
        report("producerFaces", JSON.stringify(producer?.getConnectedFaces()));
        report("producerStrong", producer?.getStronglyPoweredFace() ?? "undefined");
        report("producerOnStairs", block.getComponent("minecraft:redstone_producer") ?? "undefined");
        report("redstoneNeighbour", overworld.getBlock({ x: 6, y: 70, z: 1 }).getRedstonePower());

        report("components", JSON.stringify(block.getComponents().map(one => one.typeId)));
        report("inventoryComponent", block.getComponent("minecraft:inventory") ?? "undefined");
        report("inventoryHas", block.hasComponent("minecraft:inventory"));

        overworld.setBlockType({ x: 7, y: 70, z: 1 }, "bp:scripted");
        report("scriptedBlock", overworld.getBlock({ x: 7, y: 70, z: 1 })?.typeId);

        report("done", true);
    } catch (error) {
        report("threw", error && error.stack ? error.stack : String(error));
    }
}
