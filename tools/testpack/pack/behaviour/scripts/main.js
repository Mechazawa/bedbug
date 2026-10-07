// A two-page handbook built the way a 2.x pack builds one: a single CustomForm holding every page,
// each page's components shown through an observable, and buttons whose handlers turn the page by
// setting those observables. `/scriptevent "tp:handbook"` run as a player opens it.
//
// The form stays on the screen while its pages turn, so the tag left on the player once `show`
// resolves says the promise outlived every press and settled only when the form went away.
//
// The other script events are probes the game tests run as a player. Each answers by tagging that
// player with what the script read back, which is what the test asserts on.

import { BlockVolume, CameraShakeType, CloneMode, system, world } from "@minecraft/server";
import { CustomForm, ObservableBoolean, ObservableString } from "@minecraft/server-ui";

function openHandbook(player) {
    const firstPage = new ObservableBoolean(true);
    const secondPage = new ObservableBoolean(false);
    const heading = new ObservableString("Handbook, page 1 of 2");
    const turnTo = page => {
        firstPage.setData(page === 1);
        secondPage.setData(page === 2);
        heading.setData("Handbook, page " + page + " of 2");
    };

    const form = new CustomForm(player, heading)
        .label("The first page tells of embers.", { visible: firstPage })
        .button("Next page", () => turnTo(2), { visible: firstPage })
        .label("The second page tells of sprites.", { visible: secondPage })
        .button("Previous page", () => turnTo(1), { visible: secondPage })
        .button("Put the book away", () => form.close(), { visible: secondPage })
        .closeButton();

    form.show().then(reason => player.addTag("tp:handbook_" + reason));
}

/** Pushes the pack's fog twice, once tagged and once not, then takes both off again. */
function stackFogs(player) {
    const fog = player.fogSettings;
    const tagged = fog.push("tp:dusk", "probe");
    const untagged = fog.push("tp:dusk");
    player.addTag("tp:fog_pushed_" + tagged + "_" + untagged + "_" + fog.getTags().join("+"));
    const popped = fog.pop("probe");
    player.addTag("tp:fog_popped_" + popped + "_" + fog.getStack().length);
    fog.remove();
    player.addTag("tp:fog_left_" + fog.getStack().length);
}

/**
 * Copies the stone of a stone and dirt pair two blocks up, then moves the pair four blocks up.
 *
 * The message is the corner the test laid the pair at, the dirt one block east of the stone.
 */
function cloneTheRow(player, message) {
    const [x, y, z] = message.split(" ").map(Number);
    const end = { x: x + 1, y, z };
    player.dimension.cloneBlocks({ x, y, z }, end, { x, y: y + 2, z }, CloneMode.Copy,
        { includeTypes: ["minecraft:stone"] });
    player.dimension.cloneBlocks({ x, y, z }, end, { x, y: y + 4, z }, CloneMode.Move);
}

function measureTheRow(player) {
    const row = new BlockVolume({ x: 0, y: 0, z: 0 }, { x: 2, y: 0, z: 0 });
    const from = { x: 0, y: 0, z: 0 };
    player.addTag("tp:row_" + row.getClosest(1, from)[0].x + "_" + row.getFarthest(1, from)[0].x);
}

/** What a note block plays off the block at the message's position, and what that block keeps. */
function readTheBlock(player, message) {
    const [x, y, z] = message.split(" ").map(Number);
    const block = player.dimension.getBlock({ x, y, z });
    const instrument = block.getComponent("minecraft:instrument_sound");
    player.addTag("tp:plays_" + instrument.getInstrumentName("Up") + "_"
        + instrument.getInstrumentName("Down"));

    const kept = block.getComponent("minecraft:dynamic_properties");
    if (kept === undefined) {
        player.addTag("tp:keeps_nothing");
        return;
    }
    kept.set("tp:probe", 7);
    player.addTag("tp:keeps_" + kept.get("tp:probe"));
}

system.afterEvents.scriptEventReceive.subscribe(event => {
    const player = event.sourceEntity;
    if (player === undefined) return;

    switch (event.id) {
        case "tp:handbook": openHandbook(player); break;
        case "tp:fog": stackFogs(player); break;
        case "tp:clone": cloneTheRow(player, event.message); break;
        case "tp:row": measureTheRow(player); break;
        case "tp:block": readTheBlock(player, event.message); break;
        case "tp:shake":
            player.camera.addShake({ intensity: 2, duration: 30, type: CameraShakeType.Rotational });
            break;
        case "tp:still": player.camera.stopShaking(); break;
        default: break;
    }
});

world.beforeEvents.entityTamed.subscribe(event => {
    if (event.entity.hasTag("tp:untameable")) event.cancel = true;
});

world.afterEvents.entityTamed.subscribe(event => {
    if (event.tamingEntity.typeId === "minecraft:player") event.entity.addTag("tp:tamed_by_a_player");
});

world.afterEvents.entityStartSneaking.subscribe(event => event.entity.addTag("tp:crouched"));
world.afterEvents.entityStopSneaking.subscribe(event => event.entity.addTag("tp:stood_up"));
