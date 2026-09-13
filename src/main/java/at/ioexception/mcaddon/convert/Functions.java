package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock `.mcfunction` files, rewritten into the dialect Java's parser reads.
 *
 * The two command languages share most of their spelling, and the rest is what this translates: a
 * command Java names differently, an argument order Java reverses, a keyword Java replaced, and the
 * selector arguments, where Bedrock says `r` for what Java calls `distance` and `c` for `limit`.
 *
 * Java refuses to load a whole function over one line it cannot parse, so a line this cannot
 * translate is left out and its name is reported through
 * {@link at.ioexception.mcaddon.registry.AddonEntities#unsupported}. Nothing falls through: a
 * command name that is neither shared with Java, registered by this mod nor rewritten here is
 * dropped and named as well.
 */
public final class Functions {
    /**
     * Commands Java's own parser reads as they stand.
     *
     * Each is a command both editions have under the same name and with an argument order Java
     * accepts, so the line is passed through whole. `tp`, `w` and `msg` are aliases Java has too.
     */
    private static final Set<String> SHARED_WITH_JAVA = Set.of(
        "clear", "clone", "damage", "deop", "enchant", "fill", "function", "give", "help", "kick",
        "kill", "list", "locate", "loot", "me", "op", "place", "recipe", "reload", "say",
        "scoreboard", "setblock", "setworldspawn", "spawnpoint", "stop", "tag", "teleport", "tp",
        "tell", "w", "msg", "weather");

    /**
     * Commands Bedrock has that this mod registers itself, so a function may use them.
     *
     * Everything they need is already here: the entity events, the script bridge, the pack's own
     * animations and particles, and the mob event rules.
     */
    private static final Set<String> REGISTERED_HERE = Set.of(
        "playanimation", "event", "scriptevent", "music", "toggledownfall", "structure", "mobevent",
        "particle", "gametest");

    /**
     * Commands Bedrock has and Java has no counterpart for.
     *
     * Most name a renderer feature Java has none of, or a server Java does not speak to. `schedule`
     * is here because the two editions mean different things by the word: Bedrock delays until an
     * area loads, Java delays a function by a time. `transfer` and `save query` report progress
     * Java's own commands do not carry.
     */
    private static final Set<String> BEDROCK_ONLY = Set.of(
        "aimassist", "camera", "camerashake", "changesetting", "clearspawnpoint", "connect",
        "controlscheme", "dialogue", "editor-allowlist", "fog", "hud", "immutableworld",
        "inputpermission", "packstack", "permission", "ops", "project", "reloadconfig",
        "reloadpacketlimitconfig", "schedule", "script", "sendshowstoreoffer", "setmaxplayers",
        "transfer", "wsserver");

    /**
     * Commands Java says the same thing with under another name, and nothing else.
     *
     * The rest of the line follows the new name untouched, so every form of the Bedrock command is
     * still reachable. A `testfor` is Java's `execute if`, which returns the same success and so
     * drives the same chain command behind it.
     */
    private static final Map<String, String> RENAMED = Map.of(
        "allowlist", "whitelist",
        "whitelist", "whitelist",
        "?", "help",
        "testfor", "execute if entity",
        "testforblock", "execute if block",
        "testforblocks", "execute if blocks");

    /** Selector arguments Java spells the same way and reads the same values for. */
    private static final Set<String> SHARED_SELECTOR_ARGUMENTS = Set.of(
        "x", "y", "z", "dx", "dy", "dz", "scores", "tag", "name");

    /**
     * Selector arguments Java has no form of.
     *
     * Each narrows a selection by something Java's parser cannot express, so a line carrying one
     * would match more entities than the pack asked for. The line is left out instead.
     */
    private static final Set<String> BEDROCK_ONLY_SELECTOR_ARGUMENTS = Set.of(
        "hasitem", "haspermission", "has_property");

    /**
     * The rules both editions have, under the name {@link Platform} looks one up by.
     *
     * Bedrock writes a rule name in lower case and Java's parser is case sensitive, so the lookup is
     * by the lower-cased form and this list turns it back into a name. Which name that is differs
     * between the supported versions, which is why the answer comes from `Platform` rather than from
     * here. Every rule listed exists on all four, so a function carrying one loads everywhere.
     */
    private static final Set<String> RULES_JAVA_SHARES = Set.of(
        "commandBlockOutput", "doDayLightCycle", "doEntityDrops", "doImmediateRespawn",
        "doInsomnia", "doLimitedCrafting", "doMobLoot", "doMobSpawning", "doTileDrops",
        "doWeatherCycle", "drowningDamage", "fallDamage", "fireDamage", "freezeDamage",
        "keepInventory", "maxCommandChainLength", "mobGriefing", "naturalRegeneration",
        "playersSleepingPercentage", "randomTickSpeed", "sendCommandFeedback", "showDeathMessages",
        "spawnRadius", "tntExplosionDropDecay");

    private static final Map<String, String> GAME_RULES = lowerCased(RULES_JAVA_SHARES);

    /** The words a modern `execute` starts a clause with, which tell it apart from the old form. */
    private static final Set<String> EXECUTE_CLAUSES = Set.of(
        "align", "anchored", "as", "at", "facing", "if", "in", "on", "positioned", "rotated", "run",
        "store", "summon", "unless");

    /**
     * The Java slot a Bedrock equipment slot names, for the slots Java has one for.
     *
     * `slot.armor.body`, `slot.armor`, `slot.chest`, `slot.equippable` and `slot.saddle` are absent:
     * they name a slot Java either has no id for or spells differently from one version to the next.
     */
    private static final Map<String, String> EQUIPMENT_SLOTS = Map.of(
        "slot.weapon.mainhand", "weapon.mainhand",
        "slot.weapon.offhand", "weapon.offhand",
        "slot.armor.head", "armor.head",
        "slot.armor.chest", "armor.chest",
        "slot.armor.legs", "armor.legs",
        "slot.armor.feet", "armor.feet",
        "slot.container", "container",
        "slot.hotbar", "hotbar",
        "slot.inventory", "inventory",
        "slot.enderchest", "enderchest");

    /** The Java slots numbered one per place, which take the Bedrock slot index after a dot. */
    private static final Set<String> NUMBERED_SLOTS =
        Set.of("container", "hotbar", "inventory", "enderchest");

    private static final Pattern SELECTOR = Pattern.compile("@[aeprsn]\\[");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private Functions() {
    }

    /** The names of the functions that ran, so the caller can build the tick tag from them. */
    public static List<String> convert(BedrockPack pack, Path root, String namespace,
        Set<String> unsupported) throws IOException {
        List<String> converted = new ArrayList<>();
        String directory = Platform.singularDataDirectories()
            ? "function" : "functions";

        for (Path file : pack.list("functions", ".mcfunction")) {
            String relative = pack.root().relativize(file).toString().replace('\\', '/');
            String inside = Names.sanitise(relative.substring("functions/".length(), relative.length() - 11));

            List<String> lines = new ArrayList<>();
            for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
                String rewritten = line(line.strip(), unsupported);
                if (rewritten != null) lines.add(rewritten);
            }

            Path target = root.resolve("data").resolve(namespace).resolve(directory)
                .resolve(inside + ".mcfunction");
            Files.createDirectories(target.getParent());
            Files.writeString(target, String.join("\n", lines), StandardCharsets.UTF_8);
            converted.add(namespace + ":" + inside);
        }

        writeTickTag(pack, root, namespace, converted);
        return converted;
    }

    /** Null for a line Java would reject: a blank, or one whose command or selector cannot follow. */
    private static String line(String source, Set<String> unsupported) {
        if (source.isEmpty() || source.startsWith("#")) return source.isEmpty() ? null : source;

        String translated = translate(source.startsWith("/") ? source.substring(1) : source, unsupported);
        return translated == null ? null : selectors(translated, unsupported);
    }

    /**
     * One command in Java's dialect, or null when Java has no way to say it.
     *
     * The selectors are left for the caller, because a nested command is translated through here as
     * well and its selectors are rewritten once with the line they sit in.
     */
    private static String translate(String command, Set<String> unsupported) {
        String[] parts = SPACE.split(command.strip(), 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].strip() : "";

        if (BEDROCK_ONLY.contains(name)) {
            unsupported.add("command " + name);
            return null;
        }
        String renamed = RENAMED.getOrDefault(name, SHARED_WITH_JAVA.contains(name)
            || REGISTERED_HERE.contains(name) ? name : null);
        if (renamed != null) return rest.isEmpty() ? renamed : renamed + " " + rest;

        return rewrite(name, rest, unsupported);
    }

    /**
     * The commands whose arguments Java reads differently as well as their name.
     *
     * `titleraw` and `tellraw` take Bedrock's rawtext object, which is a text component with one
     * extra wrapper around it. The rest move an argument, replace a keyword, or fill in one Java
     * asks for and Bedrock works out for itself.
     */
    private static String rewrite(String name, String rest, Set<String> unsupported) {
        return switch (name) {
            case "summon" -> summon(rest, unsupported);
            case "execute" -> execute(rest, unsupported);
            case "titleraw" -> "title " + rawText(rest);
            case "tellraw" -> "tellraw " + rawText(rest);
            case "title" -> title(rest);
            case "daylock", "alwaysday" -> gameRule("dodaylightcycle "
                + ("false".equalsIgnoreCase(rest) ? "true" : "false"), unsupported);
            case "difficulty" -> "difficulty " + difficulty(rest);
            case "gamemode" -> gameModeCommand(rest, unsupported);
            case "gamerule" -> gameRule(rest, unsupported);
            case "effect" -> effect(rest);
            case "xp" -> experience(rest);
            case "playsound" -> playSound(rest);
            case "stopsound" -> stopSound(rest);
            case "spreadplayers" -> spreadPlayers(rest, unsupported);
            case "replaceitem" -> replaceItem(rest, unsupported);
            case "ride" -> ride(rest, unsupported);
            case "save" -> save(rest, unsupported);
            case "tickingarea" -> tickingArea(rest, unsupported);
            case "time" -> time(rest);
            default -> {
                unsupported.add("command " + name);
                yield null;
            }
        };
    }

    /** Null when the summoned entity is one Java has none of, which costs the line. */
    private static String summon(String rest, Set<String> unsupported) {
        String[] arguments = SPACE.split(rest, 2);
        String type = BedrockEntityNames.java(arguments[0]);
        if (type == null) {
            unsupported.add("command summon, which names an entity Java does not have");
            return null;
        }
        return "summon " + type + (arguments.length > 1 ? " " + arguments[1] : "");
    }

    /**
     * An `execute`, in either the modern form both editions share or the one Bedrock had before it.
     *
     * The command behind `run` is translated in its own right, so a chain ending in something Java
     * has no counterpart for costs the line rather than being written out unreadable.
     */
    private static String execute(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        if (rest.isEmpty()) {
            unsupported.add("command execute with nothing to run");
            return null;
        }
        if (!EXECUTE_CLAUSES.contains(parts[0].toLowerCase(Locale.ROOT))) {
            return legacyExecute(rest, unsupported);
        }

        int run = rest.indexOf(" run ");
        if (run < 0) return "execute " + rest;

        String inner = translate(rest.substring(run + " run ".length()), unsupported);
        return inner == null ? null : "execute " + rest.substring(0, run) + " run " + inner;
    }

    /**
     * `execute <origin> <position> [detect <position> <block> <data>] <command>`.
     *
     * Bedrock ran the command as the origin entity and at a position relative to it, which the
     * modern form says with `as`, `at` and `positioned`. The block data value has no Java form: a
     * pack asking for anything but `-1`, which Bedrock reads as any state, loses the line.
     */
    private static String legacyExecute(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        if (parts.length < 5) {
            unsupported.add("command execute in a form with no command behind it");
            return null;
        }

        StringBuilder clauses = new StringBuilder("as " + parts[0] + " at @s positioned "
            + parts[1] + " " + parts[2] + " " + parts[3]);
        int at = 4;
        if (parts[at].equalsIgnoreCase("detect")) {
            if (parts.length < at + 7) {
                unsupported.add("command execute detect with nothing to run");
                return null;
            }
            if (!parts[at + 5].equals("-1")) {
                unsupported.add("command execute detect with a block data value");
                return null;
            }
            clauses.append(" if block ").append(parts[at + 1]).append(' ').append(parts[at + 2])
                .append(' ').append(parts[at + 3]).append(' ').append(parts[at + 4]);
            at += 6;
        }

        String inner = translate(String.join(" ", Arrays.copyOfRange(parts, at, parts.length)), unsupported);
        return inner == null ? null : "execute " + clauses + " run " + inner;
    }

    /** Unwraps `{"rawtext":[...]}` into the bare component list Java's text commands read. */
    private static String rawText(String arguments) {
        int brace = arguments.indexOf('{');
        if (brace < 0) return arguments;

        try {
            JsonObject body = Json.parse(arguments.substring(brace)).getAsJsonObject();
            JsonArray raw = body.getAsJsonArray("rawtext");
            if (raw == null) return arguments;
            return arguments.substring(0, brace) + raw;
        } catch (RuntimeException error) {
            return arguments;
        }
    }

    /**
     * Bedrock's `title` takes plain text where Java takes a text component.
     *
     * `clear`, `reset` and `times` carry no text and are the same in both, so they pass through.
     */
    private static String title(String rest) {
        String[] parts = SPACE.split(rest, 3);
        if (parts.length < 3) return "title " + rest;

        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "title", "subtitle", "actionbar" ->
                "title " + parts[0] + " " + parts[1] + " " + new JsonPrimitive(parts[2]);
            default -> "title " + rest;
        };
    }

    /** Bedrock's difficulty takes a letter or a number where Java takes the word. */
    private static String difficulty(String rest) {
        return switch (rest.toLowerCase(Locale.ROOT)) {
            case "0", "p" -> "peaceful";
            case "1", "e" -> "easy";
            case "2", "n" -> "normal";
            case "3", "h" -> "hard";
            default -> rest;
        };
    }

    /** `gamemode <mode> [player]`, where Bedrock's mode may be a number or a letter. */
    private static String gameModeCommand(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        String mode = gameMode(parts[0].toLowerCase(Locale.ROOT));
        if (mode == null) {
            unsupported.add("command gamemode " + parts[0] + ", which Java has no mode for");
            return null;
        }
        return "gamemode " + mode + (parts.length > 1 ? " " + parts[1] : "");
    }

    /** `gamerule <rule> [value]`, under the name this version of Java knows the rule by. */
    private static String gameRule(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        String shared = GAME_RULES.get(parts[0].toLowerCase(Locale.ROOT));
        String java = shared == null ? null : Platform.gameRuleName(shared);
        if (java == null) {
            unsupported.add("game rule " + parts[0]);
            return null;
        }
        return "gamerule " + java + (parts.length > 1 ? " " + parts[1] : "");
    }

    /**
     * `effect <target> <effect> [seconds] [amplifier] [hideParticles]`, or `<target> clear [effect]`.
     *
     * Java asks which of the two it is first and names the target after that, so the two leading
     * arguments change places.
     */
    private static String effect(String rest) {
        String[] parts = SPACE.split(rest, 2);
        if (parts.length < 2) return "effect " + rest;

        String[] following = SPACE.split(parts[1], 2);
        if (following[0].equalsIgnoreCase("clear")) {
            return "effect clear " + parts[0] + (following.length > 1 ? " " + following[1] : "");
        }
        return "effect give " + parts[0] + " " + parts[1];
    }

    /** `xp <amount>[L] <player>`, where the trailing letter is what Java calls levels. */
    private static String experience(String rest) {
        String[] parts = SPACE.split(rest, 2);
        String amount = parts[0];
        boolean levels = amount.toLowerCase(Locale.ROOT).endsWith("l");
        if (levels) amount = amount.substring(0, amount.length() - 1);

        return "xp add " + (parts.length > 1 ? parts[1] : "@s") + " " + amount
            + (levels ? " levels" : " points");
    }

    /** Java asks which mixer category a sound belongs to, which Bedrock's own sounds all share. */
    private static String playSound(String rest) {
        String[] parts = SPACE.split(rest, 2);
        return "playsound " + parts[0] + " master " + (parts.length > 1 ? parts[1] : "@s");
    }

    /** Java asks for the category to silence, and `*` is every one of them. */
    private static String stopSound(String rest) {
        String[] parts = SPACE.split(rest, 2);
        return parts.length > 1 ? "stopsound " + parts[0] + " * " + parts[1] : "stopsound " + rest;
    }

    /** Java takes one argument Bedrock does not: whether a team is kept together. */
    private static String spreadPlayers(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 5);
        if (parts.length < 5) {
            unsupported.add("command spreadplayers with nobody to spread");
            return null;
        }
        return "spreadplayers " + parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3]
            + " false " + parts[4];
    }

    /**
     * `replaceitem`, which Java replaced with `item replace`.
     *
     * Bedrock names the slot and its index apart and Java writes them as one, and the numbered
     * slots are the only ones the index means anything for. `keep`, which Bedrock uses to fill an
     * empty slot only, has no Java form and costs the line.
     */
    private static String replaceItem(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        int named = parts[0].equalsIgnoreCase("block") ? 4 : 2;
        if (parts.length < named + 3) {
            unsupported.add("command replaceitem with no item in it");
            return null;
        }

        String slot = EQUIPMENT_SLOTS.get(parts[named].toLowerCase(Locale.ROOT));
        if (slot == null) {
            unsupported.add("command replaceitem into " + parts[named]);
            return null;
        }
        if (NUMBERED_SLOTS.contains(slot)) slot = slot + "." + parts[named + 1];

        int item = named + 2;
        if (parts[item].equalsIgnoreCase("keep")) {
            unsupported.add("command replaceitem keep, which fills an empty slot only");
            return null;
        }
        if (parts[item].equalsIgnoreCase("destroy")) item++;
        if (item >= parts.length) {
            unsupported.add("command replaceitem with no item in it");
            return null;
        }

        String where = String.join(" ", Arrays.copyOfRange(parts, 0, named));
        String what = parts[item] + (item + 1 < parts.length ? " " + parts[item + 1] : "");
        return "item replace " + where + " " + slot + " with " + what;
    }

    /** Bedrock rides through five modes and Java has the two that move a rider on and off. */
    private static String ride(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 3);
        if (parts.length < 2) {
            unsupported.add("command ride with no mode");
            return null;
        }

        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "stop_riding" -> "ride " + parts[0] + " dismount";
            case "start_riding" -> {
                if (parts.length < 3) {
                    unsupported.add("command ride start_riding with nothing to ride");
                    yield null;
                }
                yield "ride " + parts[0] + " mount " + SPACE.split(parts[2], 2)[0];
            }
            default -> {
                unsupported.add("command ride " + parts[1]);
                yield null;
            }
        };
    }

    /** Bedrock holds and resumes the world write; Java turns the same write off and on. */
    private static String save(String rest, Set<String> unsupported) {
        return switch (rest.toLowerCase(Locale.ROOT)) {
            case "hold" -> "save-off";
            case "resume" -> "save-on";
            default -> {
                unsupported.add("command save " + rest);
                yield null;
            }
        };
    }

    /**
     * A Bedrock ticking area is Java's forceload, which works in whole chunks.
     *
     * Java's takes the two corners as columns, so the heights are left out. A circular area and a
     * named one have no Java form, and neither does a preload.
     */
    private static String tickingArea(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        String mode = parts[0].toLowerCase(Locale.ROOT);

        if (mode.equals("remove_all")) return "forceload remove all";
        if (mode.equals("list")) return "forceload query";
        if (mode.equals("add") && parts.length >= 7 && !parts[1].equalsIgnoreCase("circle")) {
            return "forceload add " + parts[1] + " " + parts[3] + " " + parts[4] + " " + parts[6];
        }
        unsupported.add("command tickingarea " + mode);
        return null;
    }

    /** Java's clock has no word for the two moments either side of the day. */
    private static String time(String rest) {
        String[] parts = SPACE.split(rest, 2);
        if (parts.length < 2 || !parts[0].equalsIgnoreCase("set")) return "time " + rest;

        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "sunrise" -> "time set 23000";
            case "sunset" -> "time set 12000";
            default -> "time " + rest;
        };
    }

    /**
     * Rewrites the selector arguments whose names differ, and drops a line Java cannot select for.
     *
     * `r` and `rm` are a radius and a minimum radius, which Java writes as one `distance` range,
     * and the same pairing turns `l`, `rx` and `ry` and their minimums into one range each. Java
     * rejects a repeated argument, so the halves have to be put together rather than written twice.
     */
    private static String selectors(String command, Set<String> unsupported) {
        Matcher matcher = SELECTOR.matcher(command);
        StringBuilder result = new StringBuilder();
        int at = 0;

        while (matcher.find(at)) {
            int open = matcher.end() - 1;
            int close = closing(command, open);
            if (close < 0) break;

            String arguments = arguments(command.substring(open + 1, close), unsupported);
            if (arguments == null) return null;

            result.append(command, at, open).append(arguments);
            at = close + 1;
        }
        return result.append(command.substring(at)).toString();
    }

    /** The index of the bracket closing the one at `open`, counting the nesting in between. */
    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '[' || character == '{') depth++;
            if (character == ']' || character == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** The whole argument list, brackets and all, or null when Java has no way to select for it. */
    private static String arguments(String inside, Set<String> unsupported) {
        List<String> arguments = new ArrayList<>();
        Map<String, String[]> ranges = new LinkedHashMap<>();

        for (String argument : split(inside)) {
            String[] parts = argument.split("=", 2);
            if (parts.length != 2) {
                if (!argument.isBlank()) arguments.add(argument);
                continue;
            }
            String key = parts[0].strip();
            String value = parts[1].strip();

            switch (key) {
                case "type" -> {
                    String type = entityType(value);
                    if (type == null) {
                        unsupported.add("a selector naming an entity Java does not have");
                        return null;
                    }
                    arguments.add("type=" + type);
                }
                case "r" -> range(ranges, "distance", 1, value);
                case "rm" -> range(ranges, "distance", 0, value);
                case "l" -> range(ranges, "level", 1, value);
                case "lm" -> range(ranges, "level", 0, value);
                case "rx" -> range(ranges, "x_rotation", 1, value);
                case "rxm" -> range(ranges, "x_rotation", 0, value);
                case "ry" -> range(ranges, "y_rotation", 1, value);
                case "rym" -> range(ranges, "y_rotation", 0, value);
                case "c" -> arguments.add(value.startsWith("-")
                    ? "limit=" + value.substring(1) + ",sort=furthest" : "limit=" + value);
                case "m" -> {
                    String mode = negatable(value, Functions::gameMode);
                    if (mode == null) {
                        unsupported.add("a selector naming a game mode Java does not have");
                        return null;
                    }
                    arguments.add("gamemode=" + mode);
                }
                case "family" -> arguments.add("tag=" + value);
                default -> {
                    if (SHARED_SELECTOR_ARGUMENTS.contains(key)) {
                        arguments.add(key + "=" + value);
                    } else {
                        unsupported.add(BEDROCK_ONLY_SELECTOR_ARGUMENTS.contains(key)
                            ? "the selector argument " + key + ", which Java has no form of"
                            : "the selector argument " + key);
                        return null;
                    }
                }
            }
        }

        ranges.forEach((name, ends) -> arguments.add(name + "=" + (ends[0] == null ? "" : ends[0])
            + ".." + (ends[1] == null ? "" : ends[1])));
        return arguments.isEmpty() ? "" : "[" + String.join(",", arguments) + "]";
    }

    private static void range(Map<String, String[]> ranges, String name, int end, String value) {
        ranges.computeIfAbsent(name, ignored -> new String[2])[end] = value;
    }

    /** Splits on the commas between arguments, leaving the ones inside a score or item list alone. */
    private static List<String> split(String inside) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;

        for (int i = 0; i < inside.length(); i++) {
            char character = inside.charAt(i);
            if (character == '[' || character == '{') depth++;
            if (character == ']' || character == '}') depth--;
            if (character == ',' && depth == 0) {
                parts.add(inside.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(inside.substring(from));
        return parts;
    }

    private static String entityType(String value) {
        return negatable(value, BedrockEntityNames::java);
    }

    /** Keeps a leading `!` where Bedrock allows one, and renames what follows it. */
    private static String negatable(String value, UnaryOperator<String> rename) {
        boolean negated = value.startsWith("!");
        String renamed = rename.apply(negated ? value.substring(1) : value);
        return renamed == null ? null : (negated ? "!" : "") + renamed;
    }

    /** Null for `default`, which names the world's own mode rather than one Java can be set to. */
    private static String gameMode(String bedrock) {
        return switch (bedrock.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> "survival";
            case "1", "c", "creative" -> "creative";
            case "2", "a", "adventure" -> "adventure";
            case "3", "spectator" -> "spectator";
            case "d", "default" -> null;
            default -> bedrock;
        };
    }

    private static Map<String, String> lowerCased(Set<String> names) {
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        names.forEach(name -> byLowerCase.put(name.toLowerCase(Locale.ROOT), name));
        return Map.copyOf(byLowerCase);
    }

    /**
     * `functions/tick.json` names the functions Bedrock runs every tick; Java uses a function tag.
     */
    private static void writeTickTag(BedrockPack pack, Path root, String namespace, List<String> converted)
        throws IOException {
        Path file = pack.file("functions/tick.json");
        if (!Files.isRegularFile(file)) return;

        try {
            JsonArray values = new JsonArray();
            for (JsonElement value : Json.list(Json.parseObject(file), "values")) {
                if (!value.isJsonPrimitive()) continue;
                String name = namespace + ":" + Names.sanitise(value.getAsString());
                if (converted.contains(name)) values.add(name);
            }
            if (values.isEmpty()) return;

            JsonObject tag = new JsonObject();
            tag.add("values", values);

            String directory = Platform.singularDataDirectories()
                ? "tags/function" : "tags/functions";
            Path target = root.resolve("data/minecraft").resolve(directory).resolve("tick.json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, tag.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
        }
    }
}
