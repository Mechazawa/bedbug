// Two things a behaviour pack's script does, each read back by a client game test.
//
// A two-page handbook built the way a 2.x pack builds one: a single CustomForm holding every page,
// each page's components shown through an observable, and buttons whose handlers turn the page by
// setting those observables. `/scriptevent "tp:handbook"` run as a player opens it. The form stays
// on the screen while its pages turn, so the tag left on the player once `show` resolves says the
// promise outlived every press and settled only when the form went away.
//
// A watch on the game's own villagers, written the way a pack that replaces them writes it: in
// Bedrock's ids throughout, so a Java villager is `minecraft:villager_v2` and an experience orb is
// `minecraft:xp_orb`. Every answer is a tag on the entity it is about.

import { system, world } from "@minecraft/server";
import { CustomForm, ObservableBoolean, ObservableString } from "@minecraft/server-ui";

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

system.afterEvents.scriptEventReceive.subscribe(event => {
    const player = event.sourceEntity;
    if (player === undefined) return;
    if (event.id === "tp:handbook") openHandbook(player);
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
