package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.DialogueScene;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.state.AddonState;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The screen an NPC opens, as the screen a Bedrock form already becomes here.
 *
 * A scene states a name, a body of text and a row of buttons, which is what one of Bedrock's own
 * forms states, so a scene is sent down the same channel and drawn by the same screen. What Bedrock
 * adds is where the buttons point: each runs commands as the NPC rather than calling back into a
 * script, and `@initiator` in one of them stands for whoever is reading the screen.
 *
 * Which scene an NPC shows is the NPC's own state. Bedrock sets it from its editor screen, which
 * Java has none of, so `/dialogue change` is the way to set it and interacting with the NPC is what
 * opens whatever it was last set to.
 */
public final class Dialogues {
    /** Every scene the loaded packs declare, by the tag a command names one with. */
    private static final Map<String, DialogueScene> SCENES = new LinkedHashMap<>();

    /** The namespace of the add-on each scene came from, by the scene's tag. */
    private static final Map<String, String> WRITTEN_BY = new HashMap<>();

    /** Who is reading which scene, by the form the screen answers with. */
    private static final Map<Integer, Reading> READING = new HashMap<>();

    /**
     * Form ids count downwards from here, because a pack's own script counts upwards from one.
     *
     * The two share one channel and one screen, and the answer comes back carrying nothing but the
     * id, so the sign is what says whether a script or a scene is waiting for it.
     */
    private static int nextForm = -1;

    /** What a scene's commands write instead of naming a player, which Java has no selector for. */
    private static final String INITIATOR = "@initiator";

    private record Reading(ServerPlayer viewer, Entity npc, DialogueScene scene) {
    }

    private Dialogues() {
    }

    /** Every scene the behaviour packs ship, read once as the game starts. */
    public static void read(at.ioexception.mcaddon.resource.AddonSet set) {
        SCENES.clear();
        WRITTEN_BY.clear();
        for (BedrockPack pack : set.packs().packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            String namespace = set.namespaceOf(pack);
            DialogueScene.readAll(pack).forEach(scene -> {
                SCENES.put(scene.tag(), scene);
                if (namespace != null) WRITTEN_BY.put(scene.tag(), namespace);
            });
        }
        if (!SCENES.isEmpty()) {
            McAddon.LOGGER.info("Read {} NPC dialogue scenes.", SCENES.size());
        }
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, target, hit) ->
            hand == net.minecraft.world.InteractionHand.MAIN_HAND && interacted(player, target)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
    }

    /**
     * `/dialogue open` shows a scene now; `/dialogue change` says what the NPC shows from now on.
     *
     * The scene name is a quoted string because a tag carries a namespace as often as not, and
     * Brigadier reads a colon only inside quotes. The translation writes the quotes.
     */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dialogue")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("open")
                .then(Commands.argument("npc", EntityArgument.entity())
                    .then(Commands.argument("player", EntityArgument.players())
                        .executes(command -> open(command, null))
                        .then(Commands.argument("scene", StringArgumentType.string())
                            .suggests(Dialogues::suggestScenes)
                            .executes(command -> open(command,
                                StringArgumentType.getString(command, "scene")))))))
            .then(Commands.literal("change")
                .then(Commands.argument("npc", EntityArgument.entities())
                    .then(Commands.argument("scene", StringArgumentType.string())
                        .suggests(Dialogues::suggestScenes)
                        .executes(Dialogues::change)))));
    }

    private static CompletableFuture<Suggestions> suggestScenes(
        CommandContext<CommandSourceStack> command, SuggestionsBuilder builder) {
        SCENES.keySet().forEach(tag -> builder.suggest('"' + tag + '"'));
        return builder.buildFuture();
    }

    private static int open(CommandContext<CommandSourceStack> command, String named)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        AddonState state = ServerHooks.state();
        Entity npc = EntityArgument.getEntity(command, "npc");
        Collection<ServerPlayer> viewers = EntityArgument.getPlayers(command, "player");

        String tag = named != null ? named
            : state == null ? null : state.dialogueScene(npc.getUUID());
        DialogueScene scene = tag == null ? null : SCENES.get(tag);
        if (scene == null) {
            command.getSource().sendFailure(Component.literal("No such dialogue scene: " + tag));
            return 0;
        }

        if (named != null && state != null) state.setDialogueScene(npc.getUUID(), named);
        viewers.forEach(viewer -> show(viewer, npc, scene));
        return viewers.size();
    }

    private static int change(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        AddonState state = ServerHooks.state();
        String tag = StringArgumentType.getString(command, "scene");
        if (!SCENES.containsKey(tag)) {
            command.getSource().sendFailure(Component.literal("No such dialogue scene: " + tag));
            return 0;
        }

        Collection<? extends Entity> npcs = EntityArgument.getEntities(command, "npc");
        if (state != null) npcs.forEach(npc -> state.setDialogueScene(npc.getUUID(), tag));
        return npcs.size();
    }

    /** A right click on an NPC that has been given a scene opens it, which is Bedrock's own way in. */
    private static boolean interacted(net.minecraft.world.entity.player.Player player, Entity target) {
        AddonState state = ServerHooks.state();
        if (state == null || !(player instanceof ServerPlayer viewer)) return false;

        String tag = state.dialogueScene(target.getUUID());
        DialogueScene scene = tag == null ? null : SCENES.get(tag);
        if (scene == null) return false;

        show(viewer, target, scene);
        return true;
    }

    /**
     * Sends the scene and remembers who is reading it.
     *
     * The opening commands run before the screen is sent, which is the order Bedrock runs them in:
     * a scene that sets a scoreboard and then writes the score into its own text needs the score
     * set by the time the text is built.
     */
    private static void show(ServerPlayer viewer, Entity npc, DialogueScene scene) {
        run(scene, scene.onOpen(), npc, viewer);

        int form = nextForm--;
        READING.put(form, new Reading(viewer, npc, scene));
        Channels.sendForm(viewer, form,
            description(scene, npc.getName().getString()).toString());
    }

    /**
     * The scene as the description a form screen draws.
     *
     * A scene with no name of its own carries the NPC's, because a screen with no heading is one a
     * player cannot tell apart from the next.
     */
    static JsonObject description(DialogueScene scene, String npcName) {
        JsonObject json = new JsonObject();
        json.addProperty("title", scene.name().isBlank() ? npcName : scene.name());

        JsonArray elements = new JsonArray();
        if (!scene.text().isBlank()) {
            JsonObject label = new JsonObject();
            label.addProperty("type", "label");
            label.addProperty("text", scene.text());
            elements.add(label);
        }
        for (DialogueScene.Button button : scene.buttons()) {
            JsonObject drawn = new JsonObject();
            drawn.addProperty("type", "button");
            drawn.addProperty("text", button.name());
            elements.add(drawn);
        }
        json.add("elements", elements);
        return json;
    }

    /** Whether this form belongs to a scene rather than to a pack's own script. */
    public static boolean answers(int form) {
        return form < 0;
    }

    /**
     * What the player did with the screen.
     *
     * A pressed button runs its own commands and then the scene's closing ones, because pressing
     * one closes the screen as surely as walking away from it does. Only the player the scene was
     * shown to answers it: the id is all an answer names, and any client can send any id.
     */
    public static void respond(ServerPlayer player, int form, String answer) {
        Reading reading = READING.get(form);
        if (reading == null || !reading.viewer().getUUID().equals(player.getUUID())) return;
        READING.remove(form);
        if (reading.npc().isRemoved()) return;

        // The answer arrives from a client, so it is whatever that client sent rather than whatever
        // this mod's own screen would have written.
        JsonObject body = new JsonObject();
        try {
            com.google.gson.JsonElement parsed = Json.parse(answer);
            if (parsed.isJsonObject()) body = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            McAddon.LOGGER.debug("A dialogue answer was not readable: {}", answer);
        }

        if (body.has("selection") && !Json.bool(body, "canceled", false)) {
            int chosen = (int) Json.number(body, "selection", -1);
            List<DialogueScene.Button> buttons = reading.scene().buttons();
            if (chosen >= 0 && chosen < buttons.size()) {
                run(reading.scene(), buttons.get(chosen).commands(), reading.npc(), reading.viewer());
            }
        }
        run(reading.scene(), reading.scene().onClose(), reading.npc(), reading.viewer());
    }

    /** A player leaving takes every screen they were reading with them. */
    public static void forget(UUID viewer) {
        READING.values().removeIf(reading -> reading.viewer().getUUID().equals(viewer));
    }

    /**
     * The scene's own commands, run with the NPC's authority and at the NPC's place.
     *
     * A mob carries no authority of its own and a pack writing a command means the one the console
     * would run, which is the same reading an entity event's `queue_command` gets.
     */
    private static void run(DialogueScene scene, List<String> commands, Entity npc, Entity viewer) {
        if (!(npc.level() instanceof ServerLevel level)) return;

        String namespace = WRITTEN_BY.get(scene.tag());
        for (String command : commands) {
            PackCommand line = PackCommand.tryFrom(namespace,
                command.replace(INITIATOR, viewer.getName().getString()));
            if (line != null) line.run(Platform.commandSourceOf(npc, level), CommandReport.DATA);
        }
    }
}
