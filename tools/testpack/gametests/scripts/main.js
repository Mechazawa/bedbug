// Two tests of the kind a behaviour pack ships, one that passes and one that fails.
//
// Both build the same structure the pack ships, so a run proves that the structure was read, that
// the test's coordinates count from its corner, and that a verdict reaches whoever asked for it.
// The failing one is meant to fail: its message is what a run has to show.
//
// The passing one idles before it reads the world back, because a test area nobody stands near
// stops being loaded within a few ticks and every block in it then reads as missing.

import { register } from "@minecraft/server-gametest";

register("probe", "floor_is_stone", test => {
    test.assertBlockPresent("minecraft:stone", { x: 1, y: 0, z: 1 });
    test.setBlockType("minecraft:glass", { x: 1, y: 1, z: 1 });
    test.print("Placed the glass; waiting for it to read back.");

    test.startSequence()
        .thenIdle(20)
        .thenWait(() => test.assertBlockPresent("minecraft:glass", { x: 1, y: 1, z: 1 }))
        .thenExecute(() => test.assert(test.getBlock({ x: 1, y: 0, z: 1 }).isSolid, "The floor is not solid"))
        .thenSucceed();
})
    .structureName("probe:floor")
    .maxTicks(60)
    .tag("suite:default");

register("probe", "diamond_is_missing", test => {
    test.assertBlockPresent("minecraft:diamond_block", { x: 1, y: 1, z: 1 });
    test.succeed();
})
    .structureName("probe:floor")
    .maxTicks(20)
    .tag("suite:default");
