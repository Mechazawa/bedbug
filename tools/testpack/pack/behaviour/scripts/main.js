// What a behaviour pack's script does, each read back by a game test.
//
// A two-page handbook built the way a 2.x pack builds one: a single CustomForm holding every page,
// each page's components shown through an observable, and buttons whose handlers turn the page by
// setting those observables. `/scriptevent "tp:handbook"` run as a player opens it. The form stays
// on the screen while its pages turn, so the tag left on the player once `show` resolves says the
// promise outlived every press and settled only when the form went away.
//
// A ledger longer than any window: forty entries, then a tally the pack counts up while the form
// shows and a button to sign it at the very bottom. `/scriptevent "tp:ledger"` run as a player opens
// it, and signing tags the player.
//
// A shop's catalogue: an ActionFormData of eight hundred buttons, whose description is longer than
// the 32767 characters a plain network string carries. `/scriptevent "tp:catalogue"` run as a player
// opens it, and a tag on the player names the button that answered it.
//
// A watch on the game's own villagers, written the way a pack that replaces them writes it: in
// Bedrock's ids throughout, so a Java villager is `minecraft:villager_v2` and an experience orb is
// `minecraft:xp_orb`, and one that takes a job says so through the event Bedrock's villager raises
// then. Every answer is a tag on the entity it is about.
//
// A look at the game's own blocks in the same spelling, which a server game test reads back.
//
// The rest are probes the game tests run as a player. Each answers by tagging that player with what
// the script read back, which is what the test asserts on.

import {
    BlockPermutation, BlockVolume, CameraShakeType, CloneMode, HudElement, HudVisibility, system, world,
} from "@minecraft/server";
import { ActionFormData, CustomForm, ObservableBoolean, ObservableString } from "@minecraft/server-ui";
import { register } from "@minecraft/server-gametest";

const VILLAGER = "minecraft:villager_v2";

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

const LEDGER_ENTRIES = 40;

function openLedger(player) {
    let tallied = 0;
    const tally = new ObservableString("Tallied 0 times");
    const form = new CustomForm(player, "Ledger");
    for (let entry = 1; entry <= LEDGER_ENTRIES; entry++) form.label("Entry " + entry + " of the ledger");
    form.label(tally)
        .button("Tally", () => tally.setData("Tallied " + ++tallied + " times"))
        .button("Sign the ledger", () => {
            player.addTag("tp:ledger_signed");
            form.close();
        });
    form.show();
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

// `tp:totem` names this component, so using that block reaches the callback, and the tag it leaves
// says the player who used it was handed over as well.
system.beforeEvents.startup.subscribe(event => {
    event.blockComponentRegistry.registerCustomComponent("tp:tapped", {
        onPlayerInteract(interaction) {
            interaction.player?.addTag("tp:tapped");
        },
    });
});

// `/scriptevent "tp:names" <x> <y> <z>` reads the block there by Bedrock's id and answers in blocks
// above it: a lit pumpkin resolved under Bedrock's id one up, and a gold block two up when the block
// read back as Bedrock's `golden_rail`.
system.afterEvents.scriptEventReceive.subscribe(event => {
    if (event.id !== "tp:names") return;
    const [x, y, z] = event.message.split(" ").map(Number);
    const dimension = world.getDimension("overworld");
    const readAsGoldenRail = dimension.getBlock({ x, y, z })?.typeId === "minecraft:golden_rail";
    dimension.getBlock({ x, y: y + 1, z })?.setPermutation(BlockPermutation.resolve("minecraft:lit_pumpkin"));
    if (readAsGoldenRail) dimension.getBlock({ x, y: y + 2, z })?.setType("minecraft:gold_block");
});

// `/scriptevent "tp:hut" <x> <y> <z>` places the pack's own structure there through the structure
// manager, by the name its path gives it.
system.afterEvents.scriptEventReceive.subscribe(event => {
    if (event.id !== "tp:hut") return;
    const [x, y, z] = event.message.split(" ").map(Number);
    world.structureManager.place("tp:hut", world.getDimension("overworld"), { x, y, z });
});

// Commands written the way a pack writes them, none of which Java's parser reads as they stand: a
// selector asking about an inventory, a selector naming Bedrock's villager, a function named by its
// path alone and a command Java has nothing like. Each answer is a tag on the entity running them.
function runBedrockCommands(entity) {
    entity.runCommand("execute if entity @s[hasitem={item=diamond_sword,location=slot.weapon.mainhand}] "
        + "run tag @s add tp_holds_a_sword");
    entity.runCommand("execute unless entity @s[hasitem={item=diamond}] run tag @s add tp_has_no_diamond");
    entity.runCommand("tag @e[type=villager_v2,r=4] add tp_selected_as_villager_v2");
    entity.runCommand("function tp/relay");
    entity.runCommand("aimassist @s clear");
}

const WARES = 800;

function openCatalogue(player) {
    const catalogue = new ActionFormData().title("Catalogue");
    for (let ware = 1; ware <= WARES; ware++) catalogue.button("Ware " + ware + " of the catalogue");
    catalogue.show(player).then(response => player.addTag("tp:catalogue_" + response.selection));
}

// The script half of `/hud`, which hides the same pieces on the same player's client. What the
// script reads back about the display afterwards is a tag on that player.
function hideTheHotbar(player) {
    player.onScreenDisplay.setHudVisibility(HudVisibility.Hide, [HudElement.Hotbar]);
    if (player.onScreenDisplay.isForcedHidden(HudElement.Hotbar)) player.addTag("tp:hud_hotbar_hidden");
}

function resetTheHud(player) {
    player.onScreenDisplay.resetHudElementsVisibility();
    if (player.onScreenDisplay.getHiddenHudElements().length === 0) player.addTag("tp:hud_all_drawn");
}

// `tp:orbit` is built on a Bedrock camera Java has no counterpart for, so setting it throws.
function setTheOrbit(player) {
    try {
        player.camera.setCamera("tp:orbit");
    } catch (error) {
        if (error.message.includes("minecraft:follow_orbit")) player.addTag("tp:camera_refused");
    }
}

// Each kind of input taken away from a player or given back, named on that player by its number.
world.afterEvents.playerInputPermissionCategoryChange.subscribe(({ player, category, enabled }) =>
    player.addTag("tp:input_" + category + "_" + enabled));

system.afterEvents.scriptEventReceive.subscribe(event => {
    const player = event.sourceEntity;
    if (player === undefined) return;

    switch (event.id) {
        case "tp:handbook": openHandbook(player); break;
        case "tp:catalogue": openCatalogue(player); break;
        case "tp:ledger": openLedger(player); break;
        case "tp:commands": runBedrockCommands(player); break;
        // A pack handing out one of its own items unless the player already carries it.
        case "tp:stock_up":
            player.runCommand("execute unless entity @s[hasitem={item=tp:dust}] run give @s tp:dust");
            break;
        case "tp:villager": {
            const { x, y, z } = player.location;
            player.dimension.spawnEntity(VILLAGER, { x: x - 3, y, z }).addTag("tp:spawned_as_villager_v2");
            break;
        }
        case "tp:fog": stackFogs(player); break;
        case "tp:clone": cloneTheRow(player, event.message); break;
        case "tp:row": measureTheRow(player); break;
        case "tp:block": readTheBlock(player, event.message); break;
        case "tp:shake":
            player.camera.addShake({ intensity: 2, duration: 30, type: CameraShakeType.Rotational });
            break;
        case "tp:still": player.camera.stopShaking(); break;
        case "tp:camera":
            player.camera.setCamera("tp:overlook_east", { rotation: { x: 10, y: 180 } });
            break;
        case "tp:camera_default": player.camera.setDefaultCamera("tp:overlook"); break;
        case "tp:camera_orbit": setTheOrbit(player); break;
        case "tp:camera_clear": player.camera.clear(); break;
        case "tp:hud_hide": hideTheHotbar(player); break;
        case "tp:hud_reset": resetTheHud(player); break;
        default: break;
    }
});

world.afterEvents.entitySpawn.subscribe(({ entity }) => {
    if (entity.typeId === VILLAGER) entity.addTag("tp:spawn_seen");
    if (entity.typeId === "minecraft:xp_orb") entity.addTag("tp:xp_orb_seen");
    // A companion is marked by the code that spawned it, after `spawnEntity` returned, so only an
    // event delivered once that code has finished finds the mark.
    if (entity.getDynamicProperty("tp:companion_of") !== undefined) {
        entity.addTag("tp:companion_marked_on_spawn");
    }
});

// Of each pair, only the listener asking for a villager may hear one being hurt.
function tagWhenHurt(signal, tag, options) {
    signal.subscribe(({ hurtEntity }) => system.run(() => hurtEntity.addTag(tag)), options);
}
tagWhenHurt(world.afterEvents.entityHurt, "tp:hurt_as_villager", { entityTypes: [VILLAGER] });
tagWhenHurt(world.afterEvents.entityHurt, "tp:hurt_as_zombie", { entityTypes: ["minecraft:zombie"] });
tagWhenHurt(world.beforeEvents.entityHurt, "tp:before_hurt_as_villager", { entityFilter: { type: VILLAGER } });
tagWhenHurt(world.beforeEvents.entityHurt, "tp:before_hurt_as_zombie",
    { entityFilter: { type: "minecraft:zombie" } });

// A villager that takes a job raises the `minecraft:become_*` event its profession names, and a pack
// dressing its own model for that job reads the profession, the biome and the face off it then.
world.afterEvents.dataDrivenEntityTrigger.subscribe(({ entity, eventId }) => {
    if (!eventId.startsWith("minecraft:become_")) return;
    entity.addTag("tp:" + eventId.slice("minecraft:".length));
    for (const component of ["variant", "mark_variant", "skin_id"]) {
        entity.addTag("tp:" + component + "_" + (entity.getComponent("minecraft:" + component)?.value ?? "none"));
    }
}, { entityTypes: [VILLAGER] });

// Each villager the query finds once gets a sprite of this pack's own standing beside it.
system.runInterval(() => {
    for (const villager of world.getDimension("overworld").getEntities({ type: VILLAGER })) {
        if (villager.hasTag("tp:found_by_query")) continue;
        villager.addTag("tp:found_by_query");
        const companion = villager.dimension.spawnEntity("tp:sprite", villager.location);
        companion.setDynamicProperty("tp:companion_of", villager.id);
        companion.addTag("tp:villager_companion");
    }
});

// A villager somebody uses is held where it stands for two seconds, the way a pack keeps one still
// while it speaks: teleported onto its own position every tick. Bedrock's villager goes on trading
// through that.
const HELD_TICKS = 40;
world.beforeEvents.playerInteractWithEntity.subscribe(({ target }) => {
    if (target.typeId !== VILLAGER) return;
    let held = 0;
    const hold = system.runInterval(() => {
        if (!target.isValid || ++held > HELD_TICKS) {
            system.clearRun(hold);
            return;
        }
        target.teleport(target.location);
    });
});

// A pack keys its own maps by the objects `spawnEntity` hands back and finds them again through a
// query, so a sprite this script spawned has to come back from `getEntities` as that same object,
// whatever its place in the list.
const spawnedSprites = new Set();
system.afterEvents.scriptEventReceive.subscribe(event => {
    if (event.id !== "tp:sprites" || event.sourceEntity === undefined) return;
    const { x, y, z } = event.sourceEntity.location;
    for (let offset = 2; offset <= 6; offset += 2) {
        spawnedSprites.add(event.sourceEntity.dimension.spawnEntity("tp:sprite", { x: x + offset, y, z: z - 6 }));
    }
});
system.runInterval(() => {
    for (const sprite of world.getDimension("overworld").getEntities({ type: "tp:sprite" })) {
        if (spawnedSprites.has(sprite)) sprite.addTag("tp:same_object_as_spawned");
    }
});

// `tp:tally` names this component the way a 1.21.90 pack does, as a key of its own with its
// parameters as the body, and using the item tags whoever used it with the step it was given.
system.beforeEvents.startup.subscribe(event => {
    event.itemComponentRegistry.registerCustomComponent("tp:counting", {
        onUse(use, { params }) {
            use.source.addTag("tp:counted_" + params.step);
        },
    });
});

world.beforeEvents.entityTamed.subscribe(event => {
    if (event.entity.hasTag("tp:untameable")) event.cancel = true;
});

world.afterEvents.entityTamed.subscribe(event => {
    if (event.tamingEntity.typeId === "minecraft:player") event.entity.addTag("tp:tamed_by_a_player");
});

world.afterEvents.entityStartSneaking.subscribe(event => event.entity.addTag("tp:crouched"));
world.afterEvents.entityStopSneaking.subscribe(event => event.entity.addTag("tp:stood_up"));

// Two tests of this pack's own, which `/gametest runthis` and `runsetuntilfail` run. The first passes
// wherever the hut is built. The second passes the first time it runs and fails every time after,
// which is what stops a set run until it fails after its second round.
register("tp", "hut_stands", test => {
    test.assertBlockPresent("minecraft:stone", { x: 1, y: 0, z: 1 });
    test.succeed();
})
    .structureName("tp:hut")
    .maxTicks(40)
    .tag("tp:commands");

let roundsRun = 0;
register("tp", "fails_the_second_time", test => {
    roundsRun++;
    if (roundsRun > 1) test.fail("Ran " + roundsRun + " times.");
    else test.succeed();
})
    .structureName("tp:hut")
    .maxTicks(40)
    .tag("tp:until_fail");
