// The field guide is an action form with a page per critter, opened by `/scriptevent bb:guide`
// run as a player, or as the player joins when the world's settings ask for it.
// `/scriptevent bb:swarm` sets down a swarm of bedbugs round whoever sent it, as many and in the
// colours the world's settings name.

import { system, world } from "@minecraft/server";
import { ActionFormData } from "@minecraft/server-ui";

const PAGES = [
    {
        button: "Bedbugs",
        title: "Bedbugs",
        body: "Round, shiny and always busy. Bedbugs come in chestnut, rose and plum, and will follow"
            + " anybody holding a honey bun.",
    },
    {
        button: "Lumen moths",
        title: "Lumen moths",
        body: "Lumen moths hover in place and leave a trail of glowing motes behind them. Your own"
            + " settings decide how fast their wings beat.",
    },
    {
        button: "Toadstools",
        title: "Toadstools",
        body: "A toadstool gives off a soft light, enough to keep a picnic going after dark.",
    },
    {
        button: "Patchwork quilts",
        title: "Patchwork quilts",
        body: "Lay a few quilts in the grass and the bedbugs will make themselves at home.",
    },
];

function openGuide(player) {
    const guide = new ActionFormData()
        .title("Bedbug Field Guide")
        .body("Everything that crawls, flutters and glows in the Bedbug Showcase. Pick a page to read more.");
    PAGES.forEach(page => guide.button(page.button));
    guide.button("Close");
    guide.show(player).then(response => {
        const page = PAGES[response.selection];
        if (response.canceled || page === undefined) return;
        new ActionFormData().title(page.title).body(page.body).button("Back to the guide")
            .show(player).then(back => {
                if (!back.canceled && back.selection === 0) openGuide(player);
            });
    });
}

const COLOURS = ["chestnut", "rose", "plum"];

function swarm(player) {
    const settings = world.getPackSettings();
    const count = Number(settings["bb:swarm_size"] ?? 6);
    const colours = settings["bb:swarm_colours"] ?? "mixed";
    for (let index = 0; index < count; index++) {
        const angle = (index / count) * Math.PI * 2;
        const at = player.location;
        const bedbug = player.dimension.spawnEntity("bb:bedbug",
            { x: at.x + Math.cos(angle) * 2.5, y: at.y, z: at.z + Math.sin(angle) * 2.5 });
        const colour = colours === "mixed" ? COLOURS[index % COLOURS.length] : colours;
        bedbug.triggerEvent("bb:become_" + colour);
    }
}

system.afterEvents.scriptEventReceive.subscribe(event => {
    const player = event.sourceEntity;
    if (player === undefined) return;
    if (event.id === "bb:guide") openGuide(player);
    if (event.id === "bb:swarm") swarm(player);
});

world.afterEvents.playerSpawn.subscribe(event => {
    if (event.initialSpawn && world.getPackSettings()["bb:guide_on_join"] === true) {
        system.runTimeout(() => openGuide(event.player), 40);
    }
});
