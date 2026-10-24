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
    BlockPermutation, BlockVolume, CameraShakeType, CatmullRomSpline, CloneMode, EasingType, EntityAttachPoint,
    HudElement, HudVisibility, ItemStack, system, world,
} from "@minecraft/server";
import {
    ActionFormData, CustomForm, MessageFormData, ModalFormData, ObservableBoolean, ObservableNumber, ObservableString,
} from "@minecraft/server-ui";
import { register } from "@minecraft/server-gametest";
import { weighTheProbe } from "./weigh.js";
import { dedicatedServer, deopPlayer, opPlayer, secrets, SecretString, variables } from "@minecraft/server-admin";

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

// `/scriptevent "tp:cleared" <x> <y> <z>` schedules a run that gold-blocks the position and a run
// scheduled earlier for the same tick that clears it, so the gold block means the clear was ignored.
system.afterEvents.scriptEventReceive.subscribe(event => {
    if (event.id !== "tp:cleared") return;
    const [x, y, z] = event.message.split(" ").map(Number);
    let victim = -1;
    system.run(() => system.clearRun(victim));
    victim = system.run(() => world.getDimension("overworld").getBlock({ x, y, z })?.setType("minecraft:gold_block"));
});

// `/scriptevent "tp:hut" <x> <y> <z>` places the pack's own structure there through the structure
// manager, by the name its path gives it.
system.afterEvents.scriptEventReceive.subscribe(event => {
    if (event.id !== "tp:hut") return;
    const [x, y, z] = event.message.split(" ").map(Number);
    world.structureManager.place("tp:hut", world.getDimension("overworld"), { x, y, z });
});

system.afterEvents.scriptEventReceive.subscribe(event => {
    if (event.id === "tp:weigh") weighTheProbe(Number(event.message));
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
    entity.runCommand("sendshowstoreoffer @s marketplace tp_offer");
}

const WARES = 800;

function openCatalogue(player) {
    const catalogue = new ActionFormData().title("Catalogue");
    for (let ware = 1; ware <= WARES; ware++) catalogue.button("Ware " + ware + " of the catalogue");
    catalogue.show(player).then(response => player.addTag("tp:catalogue_" + response.selection));
}

// Six tiles in an action form. A title starting with "tp_tiles:" is one the resource pack's
// `server_form.json` lays out as a grid; `/scriptevent tp:tiles plain` sends the same tiles under a
// title it leaves alone. The tag on the player names the tile that answered.
function openTiles(player, message) {
    const tiles = new ActionFormData()
        .title(message === "plain" ? "Plain tiles" : "tp_tiles:Tiles")
        .body("Pick a tile");
    for (let tile = 1; tile <= 6; tile++) tiles.button("Tile " + tile, "textures/items/berry");
    tiles.show(player).then(response => player.addTag("tp:tiles_" + response.selection));
}

// Forms whose controls carry hover text, written in each generation: a 1.x ModalFormData with a
// tooltip on every input, a two-button MessageFormData with one on each button, and a 2.x CustomForm
// with a description on its inputs, a tooltip on a button and a description on each dropdown item.
// `/scriptevent tp:hints modal|message|custom` opens one.
function openHints(player, kind) {
    if (kind === "modal") {
        new ModalFormData().title("Modal hints")
            .toggle("Lamp", { tooltip: "Lights the lamp" })
            .dropdown("Colour", ["Red", "Blue"], { tooltip: { rawtext: [{ text: "Picks " }, { text: "a colour" }] } })
            .slider("Volume", 0, 10, { tooltip: "How loud" })
            .textField("Name", "Type here", { tooltip: "What to call it" })
            .show(player);
    } else if (kind === "message") {
        new MessageFormData().title("Message hints").body("Sure?")
            .button1("Yes", "Agrees").button2("No", "Declines")
            .show(player);
    } else {
        const choice = new ObservableNumber(0);
        new CustomForm(player, "Custom hints")
            .toggle("Echo", new ObservableBoolean(false), { description: "Repeats sounds" })
            .dropdown("Shape", choice, [
                { label: "Round", value: 0, description: "Has no corners" },
                { label: "Square", value: 1, description: "Has four corners" },
            ], { description: "Pick a shape" })
            .button("Apply", () => {}, { tooltip: "Keeps the shape" })
            .closeButton()
            .show();
    }
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

// The nearest wisp drawn unsettled for this player alone. A wisp settles a second after it arrives,
// and its own value, and what every other player is shown, stay settled. A property the wisp does not
// declare is refused.
function overrideTheWisp(player, overridden) {
    const [wisp] = player.dimension.getEntities({ type: "tp:wisp", location: player.location, closest: 1 });
    if (wisp === undefined) return;
    if (!overridden) {
        player.clearPropertyOverridesForEntity(wisp);
        return;
    }
    player.setPropertyOverrideForEntity(wisp, "tp:settled", false);
    try {
        player.setPropertyOverrideForEntity(wisp, "tp:undeclared", true);
    } catch {
        player.addTag("tp:override_refused");
    }
}

// The armour stand a camera probe looks at, follows or hangs off: the one nearest the player.
function nearestStand(player) {
    return player.dimension.getEntities({ type: "minecraft:armor_stand", location: player.location, closest: 1 })[0];
}

// The camera probes. Each states what a Bedrock script states, and the client test reads the camera
// it leaves behind.
function pointTheCamera(player, probe) {
    const stand = nearestStand(player);
    switch (probe) {
        case "tp:camera_orbit": player.camera.setCamera("tp:orbit", { viewOffset: { x: 0, y: 3 } }); break;
        case "tp:camera_boom":
            player.camera.setCamera("minecraft:fixed_boom",
                { entityOffset: { x: 0, y: 2, z: 0 }, viewOffset: { x: 1, y: 0 } });
            break;
        case "tp:camera_attach": player.camera.attachToEntity({ entity: stand, locator: EntityAttachPoint.Feet }); break;
        case "tp:camera_target":
            player.camera.setCamera("minecraft:free",
                { targetEntity: stand, offsetFromTargetCenter: { x: 0, y: 3, z: 0 } });
            break;
        case "tp:camera_spline": playTheSpline(player); break;
        default: break;
    }
}

// Four points over the platform, the camera arriving at the last one turned to a pitch of 10 and a
// yaw of -90. Catmull-Rom passes through every point it is given, the first and the last included.
function playTheSpline(player) {
    const curve = new CatmullRomSpline();
    curve.controlPoints = [
        { x: 0.5, y: 121, z: 0.5 }, { x: 4.5, y: 125, z: 0.5 }, { x: 8.5, y: 125, z: 4.5 }, { x: 8.5, y: 121, z: 8.5 },
    ];
    player.camera.playAnimation(curve, {
        animation: {
            progressKeyFrames: [
                { timeSeconds: 0, alpha: 0 },
                { timeSeconds: 8, alpha: 1, easingFunc: EasingType.InOutSine },
            ],
            rotationKeyFrames: [
                { timeSeconds: 0, rotation: { x: 0, y: 0, z: 0 } },
                { timeSeconds: 8, rotation: { x: 10, y: -90, z: 0 } },
            ],
        },
        totalTimeSeconds: 2,
    });
}

// Each kind of input taken away from a player or given back, named on that player by its number.
world.afterEvents.playerInputPermissionCategoryChange.subscribe(({ player, category, enabled }) =>
    player.addTag("tp:input_" + category + "_" + enabled));

// What `config/` hands this pack: two variables, one of them overridden by the module's own folder,
// and a secret whose value has to stay out of everything the script can see.
function readTheConfig(player) {
    player.addTag(`tp:config_${variables.get("tp_shared")}_${variables.get("tp_greeting")}`);
    player.addTag(`tp:dedicated_${dedicatedServer !== undefined}`);
    const token = secrets.get("tp_token");
    player.addTag(`tp:secret_${secrets.names.join("+")}_${token instanceof SecretString}`);
    player.addTag(`tp:secret_shown_${JSON.stringify(token)}${String(token)}${Object.getOwnPropertyNames(token)}`);
}

// Tags the player with each of the behaviour pack's own settings as `world.getPackSettings` reads
// it, dropping the tags an earlier reading left.
function readThePackSettings(player) {
    player.getTags().filter(tag => tag.startsWith("tp:setting_read_")).forEach(tag => player.removeTag(tag));
    const settings = world.getPackSettings();
    ["tp:ember_glows", "tp:ember_heat", "tp:ember_mood"]
        .forEach(name => player.addTag(`tp:setting_read_${name.slice(3)}_${settings[name]}`));
}

// A player's own store, counted up on each first spawn into the world, the way a pack keeps a
// player's choices: read and written while the player is joining.
world.afterEvents.playerSpawn.subscribe(({ player, initialSpawn }) => {
    if (!initialSpawn) return;
    const visits = player.getDynamicProperty("tp:visits");
    player.setDynamicProperty("tp:visits", (typeof visits === "number" ? visits : 0) + 1);
});

// A setting the world's owner changes reaches a running script at once.
world.afterEvents.packSettingChange.subscribe(({ settingName, settingValue }) => {
    world.getPlayers().forEach(player => player.addTag(`tp:setting_heard_${settingName.slice(3)}_${settingValue}`));
});

// `/scriptevent tp:board` seats a sprite in the empty boat nearest the player through the boat's own
// `minecraft:rideable`, the way a pack moves a model of its own into the boat a villager boarded.
function boardTheBoat(player) {
    const boat = player.dimension.getEntities({ type: "minecraft:boat", location: player.location, closest: 1 })[0];
    const seats = boat?.getComponent("minecraft:rideable");
    if (seats === undefined) {
        player.addTag("tp:boat_without_seats");
        return;
    }
    const { x, y, z } = boat.location;
    const sprite = player.dimension.spawnEntity("tp:sprite", { x, y: y + 2, z });
    sprite.addTag("tp:boarded");
    seats.addRider(sprite);
}

// Makes the player an operator twice and takes it away twice, tagging what each answered.
function swapOperator(player) {
    const attempt = (name, call) => {
        try {
            call(player);
            player.addTag(`tp:${name}_done`);
        } catch (error) {
            player.addTag(`tp:${name}_${error.constructor.name}`);
        }
    };
    attempt("op", opPlayer);
    attempt("op_again", opPlayer);
    attempt("deop", deopPlayer);
    attempt("deop_again", deopPlayer);
}

system.afterEvents.scriptEventReceive.subscribe(event => {
    const player = event.sourceEntity;
    if (player === undefined) return;

    switch (event.id) {
        case "tp:handbook": openHandbook(player); break;
        case "tp:catalogue": openCatalogue(player); break;
        case "tp:ledger": openLedger(player); break;
        case "tp:tiles": openTiles(player, event.message); break;
        case "tp:hints": openHints(player, event.message); break;
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
        // A pack tells a villager's job by its families, the way Bedrock's own villager states it.
        case "tp:families":
            for (const villager of player.dimension.getEntities({ location: player.location, maxDistance: 4 })) {
                if (villager.typeId !== VILLAGER && villager.typeId !== "minecraft:zombie_villager_v2") continue;
                for (const family of villager.getComponent("minecraft:type_family").getTypeFamilies()) {
                    villager.addTag("tp:family_" + family);
                }
            }
            break;
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
        case "tp:camera_orbit":
        case "tp:camera_boom":
        case "tp:camera_attach":
        case "tp:camera_target":
        case "tp:camera_spline":
            pointTheCamera(player, event.id);
            break;
        case "tp:camera_clear": player.camera.clear(); break;
        case "tp:hud_hide": hideTheHotbar(player); break;
        case "tp:hud_reset": resetTheHud(player); break;
        case "tp:board": boardTheBoat(player); break;
        case "tp:override": overrideTheWisp(player, true); break;
        case "tp:override_clear": overrideTheWisp(player, false); break;
        case "tp:control_scheme": steer(player, event.message); break;
        case "tp:config": readTheConfig(player); break;
        case "tp:config_reload": dedicatedServer?.reloadScriptingConfig(); readTheConfig(player); break;
        case "tp:operator": swapOperator(player); break;
        case "tp:item_tags": askItemTags(player); break;
        case "tp:pack_settings": readThePackSettings(player); break;
        case "tp:vector_property": keepAVector(player); break;
        default: break;
    }
});

// `/scriptevent tp:item_tags` asks stacks about Bedrock's vanilla item tags and tags the player with
// each answer: a sign is a `minecraft:sign` and an axe a `minecraft:is_axe`, and neither is the other.
function askItemTags(player) {
    const sign = new ItemStack("minecraft:oak_sign");
    const axe = new ItemStack("minecraft:iron_axe");
    const answers = {
        sign_is_sign: sign.hasTag("minecraft:sign"),
        sign_is_axe: sign.hasTag("minecraft:is_axe"),
        axe_is_axe: axe.hasTag("minecraft:is_axe"),
        axe_is_tool: axe.hasTag("minecraft:is_tool"),
        axe_listed: axe.getTags().includes("minecraft:is_axe"),
    };
    Object.entries(answers).forEach(([name, answer]) => player.addTag(`tp_itemtag_${name}_${answer}`));
}

// `/scriptevent tp:vector_property` stores a Vector3 and a string that looks like one, and tags the
// player with what each comes back as.
function keepAVector(player) {
    player.setDynamicProperty("tp:vector", { x: 1, y: 2.5, z: -3 });
    player.setDynamicProperty("tp:looks_like_one", "1,2.5,-3");
    const vector = player.getDynamicProperty("tp:vector");
    player.addTag(`tp:vector_${typeof vector}_${vector.x}_${vector.y}_${vector.z}`);
    player.addTag(`tp:text_${typeof player.getDynamicProperty("tp:looks_like_one")}`);
}

// `/scriptevent tp:control_scheme <scheme>` puts the player on a control scheme, or back on their
// camera's for `clear`, and tags them with the scheme they are on, or with the refusal.
function steer(player, scheme) {
    player.getTags().filter(tag => tag.startsWith("tp_control_")).forEach(tag => player.removeTag(tag));
    try {
        player.setControlScheme(scheme === "clear" ? undefined : scheme);
        player.addTag("tp_control_" + player.getControlScheme());
    } catch (error) {
        player.addTag("tp_control_refused");
    }
}

world.afterEvents.entitySpawn.subscribe(({ entity, cause }) => {
    if (entity.typeId === "minecraft:cow") entity.addTag("tp:cow_cause_" + cause);
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

// Only an iron sword is heard being picked up, and nobody may pick up a diamond one.
world.beforeEvents.entityItemPickup.subscribe(({ entity, item }) => {
    const typeId = item.getComponent("minecraft:item").itemStack.typeId;
    system.run(() => entity.addTag("tp:taking_" + typeId.slice("minecraft:".length)));
}, { itemFilter: { includeTypes: ["iron_sword"] } });
world.afterEvents.entityItemPickup.subscribe(({ entity, items }) =>
    entity.addTag("tp:took_" + items.map(stack => stack.typeId.slice("minecraft:".length) + "_" + stack.amount).join("+")),
{ itemFilter: { includeTypes: ["minecraft:iron_sword"] } });
world.beforeEvents.entityItemPickup.subscribe(event => {
    event.cancel = true;
}, { itemFilter: { includeTypes: ["minecraft:diamond_sword"] } });

// A villager that takes a job raises the `minecraft:become_*` event its profession names, and a pack
// dressing its own model for that job reads the profession, the biome and the face off it then.
world.afterEvents.dataDrivenEntityTrigger.subscribe(({ entity, eventId }) => {
    if (!eventId.startsWith("minecraft:become_")) return;
    entity.addTag("tp:" + eventId.slice("minecraft:".length));
    for (const component of ["variant", "mark_variant", "skin_id"]) {
        entity.addTag("tp:" + component + "_" + (entity.getComponent("minecraft:" + component)?.value ?? "none"));
    }
}, { entityTypes: [VILLAGER] });

// The events Mojang's own behaviour files raise on the game's mobs, and the group changes an event
// of the pack's own made, are all heard through the same signal.
const HEARD_EVENTS = ["spawn_from_village", "from_wandering_trader", "entity_transformed", "start_celebrating",
    "on_sheared"];
world.afterEvents.dataDrivenEntityTrigger.subscribe(event => {
    const { entity, eventId } = event;
    const name = eventId.replace("minecraft:", "");
    if (HEARD_EVENTS.includes(name)) entity.addTag("tp:heard_" + name);
    for (const modifier of event.getModifiers()) {
        for (const group of modifier.addedComponentGroups) entity.addTag("tp:modifier_added_" + group);
    }
});
// A young mob of the game's own raises the event its `minecraft:ageable` names as it grows up, which is
// how a pack drawing its own model of a villager learns to draw the adult. The rabbit names `grow_up`.
world.afterEvents.dataDrivenEntityTrigger.subscribe(({ entity, eventId }) => {
    entity.addTag("tp:heard_" + eventId.replace("minecraft:", ""));
    if (entity.getComponent("minecraft:is_baby") === undefined) entity.addTag("tp:grown_when_heard");
}, { eventTypes: ["minecraft:ageable_grow_up", "grow_up"] });

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
