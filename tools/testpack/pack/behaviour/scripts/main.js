// What a behaviour pack's script does, each read back by a game test.
//
// A two-page handbook built the way a 2.x pack builds one: a single CustomForm holding every page,
// each page's components shown through an observable, and buttons whose handlers turn the page by
// setting those observables. `/scriptevent "tp:handbook"` run as a player opens it. The form stays
// on the screen while its pages turn, so the tag left on the player once `show` resolves says the
// promise outlived every press and settled only when the form went away.
//
// A shop's catalogue: an ActionFormData of eight hundred buttons, whose description is longer than
// the 32767 characters a plain network string carries. `/scriptevent "tp:catalogue"` run as a player
// opens it, and a tag on the player names the button that answered it.
//
// A watch on the game's own villagers, written the way a pack that replaces them writes it: in
// Bedrock's ids throughout, so a Java villager is `minecraft:villager_v2` and an experience orb is
// `minecraft:xp_orb`. Every answer is a tag on the entity it is about.
//
// A look at the game's own blocks in the same spelling, which a server game test reads back.

import { BlockPermutation, system, world } from "@minecraft/server";
import { ActionFormData, CustomForm, ObservableBoolean, ObservableString } from "@minecraft/server-ui";

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

// Commands written the way a pack writes them, none of which Java's parser reads as they stand: a
// selector asking about an inventory, a selector naming Bedrock's villager, a function named by its
// path alone and a command Java has nothing like. Each answer is a tag on the entity running them.
function runBedrockCommands(entity) {
    entity.runCommand("execute if entity @s[hasitem={item=diamond_sword,location=slot.weapon.mainhand}] "
        + "run tag @s add tp_holds_a_sword");
    entity.runCommand("execute unless entity @s[hasitem={item=diamond}] run tag @s add tp_has_no_diamond");
    entity.runCommand("tag @e[type=villager_v2,r=4] add tp_selected_as_villager_v2");
    entity.runCommand("function tp/relay");
    entity.runCommand("hud @s hide hotbar");
}

const WARES = 800;

function openCatalogue(player) {
    const catalogue = new ActionFormData().title("Catalogue");
    for (let ware = 1; ware <= WARES; ware++) catalogue.button("Ware " + ware + " of the catalogue");
    catalogue.show(player).then(response => player.addTag("tp:catalogue_" + response.selection));
}

system.afterEvents.scriptEventReceive.subscribe(event => {
    const player = event.sourceEntity;
    if (player === undefined) return;
    if (event.id === "tp:commands") runBedrockCommands(player);
    // A pack handing out one of its own items unless the player already carries it.
    if (event.id === "tp:stock_up") {
        player.runCommand("execute unless entity @s[hasitem={item=tp:dust}] run give @s tp:dust");
    }
    if (event.id === "tp:handbook") openHandbook(player);
    if (event.id === "tp:catalogue") openCatalogue(player);
    if (event.id === "tp:villager") {
        const { x, y, z } = player.location;
        player.dimension.spawnEntity(VILLAGER, { x: x - 3, y, z }).addTag("tp:spawned_as_villager_v2");
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
