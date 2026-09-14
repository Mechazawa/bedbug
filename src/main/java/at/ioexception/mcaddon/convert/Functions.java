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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
        "clear", "clone", "deop", "enchant", "fill", "function", "give", "help", "kick", "kill",
        "list", "me", "msg", "op", "recipe", "reload", "say", "scoreboard", "setblock",
        "setworldspawn", "spawnpoint", "stop", "tag", "teleport", "tell", "tp", "w", "weather");

    /**
     * Commands Bedrock has that this mod registers itself, so a function may use them.
     *
     * Everything they need is already here: the entity events, the script bridge, the pack's own
     * animations and particles, and the mob event rules.
     */
    private static final Set<String> REGISTERED_HERE = Set.of(
        "playanimation", "event", "scriptevent", "music", "toggledownfall", "structure", "mobevent",
        "particle", "gametest", "dialogue", "transfer");

    /**
     * The registered commands whose Bedrock line the command built here reads as it stands.
     *
     * The other six are rewritten, because a Bedrock form the command built here cannot read is a
     * line Java's parser rejects, and one of those costs the whole function.
     */
    private static final Set<String> REGISTERED_WHOLE_LINE =
        Set.of("playanimation", "event", "toggledownfall", "transfer");

    /**
     * Commands Bedrock has and Java has no counterpart for.
     *
     * Most name a renderer feature Java has none of, or a server Java does not speak to. `schedule`
     * is here because the two editions mean different things by the word: Bedrock delays until an
     * area loads, Java delays a function by a time. `transfer` and `save query` report progress
     * Java's own commands do not carry.
     */
    private static final Set<String> BEDROCK_ONLY = Set.of(
        "aimassist", "camera", "camerashake", "clearspawnpoint", "connect",
        "controlscheme", "editor-allowlist", "fog", "hud", "immutableworld",
        "inputpermission", "packstack", "permission", "ops", "project", "reloadconfig",
        "reloadpacketlimitconfig", "script", "sendshowstoreoffer", "setmaxplayers",
        "wsserver");

    /**
     * Commands whose rewrite changes the front of a line and leaves the rest as the pack wrote it.
     *
     * Java's own parser reads every word further along, so which of those forms survive is its
     * answer rather than anything spelled out here: `effect ... infinite`, `execute if blocks ...
     * masked`, `loot spawn`, `time query gametime` and `title @a times` all reach it as written.
     */
    private static final Set<String> TAIL_REACHES_JAVA =
        Set.of("effect", "execute", "locate", "loot", "schedule", "time", "title", "titleraw");

    /**
     * Forms of a command this mod does translate that Java has no way to say.
     *
     * Each is written `<command> <keyword>`, which is how the published command metadata names one.
     * A line carrying one is left out and the form is reported, because handing it to Java would
     * give its parser something it rejects and cost the whole function.
     */
    private static final Set<String> BEDROCK_ONLY_KEYWORDS = Set.of(
        "changesetting allow-cheats", "changesetting false", "changesetting true",
        "gamerule functioncommandlimit",
        "gametest create", "gametest pos", "gametest runsetuntilfail", "gametest runthese",
        "gametest runthis",
        "loot slot.equippable",
        "mobevent minecraft:ender_dragon_event",
        "music loop", "music volume",
        "place apply_waterlogging", "place featurerule", "place ignore_waterlogging",
        "replaceitem keep", "replaceitem slot.equippable",
        "ride evict_riders", "ride if_group_fits", "ride no_ride_change", "ride reassign_rides",
        "ride skip_riders", "ride summon_ride", "ride summon_rider", "ride teleport_ride",
        "ride until_full",
        "save query",
        "schedule on_area_loaded",
        "structure block_by_block", "structure layer_by_layer", "structure memory", "structure xz",
        "summon facing",
        "tickingarea all-dimensions", "tickingarea circle", "tickingarea preload");

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
        "commandBlockOutput", "doDayLightCycle", "doEntityDrops", "doFireTick", "doImmediateRespawn",
        "doInsomnia", "doLimitedCrafting", "doMobLoot", "doMobSpawning", "doTileDrops",
        "doWeatherCycle", "drowningDamage", "fallDamage", "fireDamage", "freezeDamage",
        "keepInventory", "maxCommandChainLength", "mobGriefing", "naturalRegeneration",
        "playersSleepingPercentage", "randomTickSpeed", "sendCommandFeedback", "showDeathMessages",
        "spawnRadius", "tntExplosionDropDecay");

    /**
     * Rules only the newer supported versions of Java have.
     *
     * `Platform` answers with a name or with nothing, so a function setting one of these translates
     * where the rule exists and loses that line, named, where it does not.
     */
    private static final Set<String> RULES_NEWER_JAVA_HAS = Set.of(
        "commandBlocksEnabled", "playerWaypoints", "projectilesCanBreakBlocks", "pvp",
        "tntExplodes");

    private static final Map<String, String> GAME_RULES = lowerCased(RULES_JAVA_SHARES,
        RULES_NEWER_JAVA_HAS);

    /** Every rule a Bedrock name reaches, which is the set a change can be reported under. */
    public static Collection<String> gameRuleNames() {
        return GAME_RULES.values();
    }

    /** The words a modern `execute` starts a clause with, which tell it apart from the old form. */
    private static final Set<String> EXECUTE_CLAUSES = Set.of(
        "align", "anchored", "as", "at", "facing", "if", "in", "on", "positioned", "rotated", "run",
        "store", "summon", "unless");

    /**
     * The Java slot a Bedrock equipment slot names, for the slots Java spells the same on all four.
     *
     * `slot.armor` counts the four armour places by index, and `slot.saddle` and `slot.armor.body`
     * are spelled differently from one version to the next, so those three are looked up instead.
     * `slot.equippable` has no Java slot: it holds whatever a Bedrock entity's own equippable
     * component puts there, which Java spreads over the armour and the saddle.
     */
    private static final Map<String, String> EQUIPMENT_SLOTS = Map.ofEntries(
        Map.entry("slot.weapon.mainhand", "weapon.mainhand"),
        Map.entry("slot.weapon.offhand", "weapon.offhand"),
        Map.entry("slot.armor.head", "armor.head"),
        Map.entry("slot.armor.chest", "armor.chest"),
        Map.entry("slot.armor.legs", "armor.legs"),
        Map.entry("slot.armor.feet", "armor.feet"),
        Map.entry("slot.chest", "horse.chest"),
        Map.entry("slot.container", "container"),
        Map.entry("slot.hotbar", "hotbar"),
        Map.entry("slot.inventory", "inventory"),
        Map.entry("slot.enderchest", "enderchest"));

    /** The four armour places in the order a Bedrock `slot.armor` index counts them. */
    private static final List<String> ARMOUR_SLOTS =
        List.of("armor.head", "armor.chest", "armor.legs", "armor.feet");

    /** The Java slots numbered one per place, which take the Bedrock slot index after a dot. */
    private static final Set<String> NUMBERED_SLOTS =
        Set.of("container", "hotbar", "inventory", "enderchest");

    /** The Java rotation a Bedrock structure rotation names. */
    private static final Map<String, String> STRUCTURE_ROTATIONS = Map.of(
        "0_degrees", "none",
        "90_degrees", "clockwise_90",
        "180_degrees", "180",
        "270_degrees", "counterclockwise_90");

    /**
     * The Java mirror a Bedrock structure mirror names.
     *
     * Java names the pair of faces a mirror swaps where Bedrock names the axis it runs along, so
     * mirroring x swaps east for west and mirroring z swaps north for south. Bedrock's `xz` mirrors
     * both at once, which Java has no single mirror for.
     */
    private static final Map<String, String> STRUCTURE_MIRRORS = Map.of(
        "none", "none",
        "x", "front_back",
        "z", "left_right");

    private static final Pattern SELECTOR = Pattern.compile("@[aeprsn]\\[");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /** Bedrock's own name for the dimension, as an `execute` clause writes it. */
    private static final Pattern NETHER_CLAUSE =
        Pattern.compile("\\bin\\s+(?:minecraft:)?nether\\b");

    private Functions() {
    }

    /** The names of the functions that ran, so the caller can build the tick tag from them. */
    public static List<String> convert(BedrockPack pack, Path root, String namespace,
        Set<String> unsupported) throws IOException {
        List<String> converted = new ArrayList<>();
        String directory = Platform.singularDataDirectories()
            ? "function" : "functions";

        for (Path file : pack.list("functions", ".mcfunction")) {
            String relative = pack.relative(file).toString().replace('\\', '/');
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
            || REGISTERED_WHOLE_LINE.contains(name) ? name : null);
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
            case "damage" -> damage(rest, unsupported);
            case "locate" -> locate(rest, unsupported);
            case "loot" -> loot(rest, unsupported);
            case "place" -> place(rest, unsupported);
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
            case "changesetting" -> changeSetting(rest, unsupported);
            case "music" -> music(rest, unsupported);
            case "structure" -> structure(rest, unsupported);
            case "mobevent" -> mobEvent(rest, unsupported);
            case "gametest" -> gameTest(rest, unsupported);
            case "particle" -> "particle " + quotedName(rest);
            case "scriptevent" -> "scriptevent " + quotedName(rest);
            case "dialogue" -> dialogue(rest, unsupported);
            case "schedule" -> schedule(rest, unsupported);
            default -> {
                unsupported.add("command " + name);
                yield null;
            }
        };
    }

    /**
     * `summon <type> [position]`, which is as much of Bedrock's own form as Java's takes.
     *
     * Bedrock turns the entity, names it, fires an event on it and aims it at something as it
     * summons it. Java reads the block of data an entity is built from instead, which a line of a
     * function has no room to assemble, so a summon writing any of the four loses its line.
     */
    private static String summon(String rest, Set<String> unsupported) {
        String[] arguments = SPACE.split(rest);
        String type = BedrockEntityNames.java(arguments[0]);
        if (type == null) {
            unsupported.add("command summon, which names an entity Java does not have");
            return null;
        }
        if (arguments.length == 1) return "summon " + type;
        if (arguments.length >= 5 && arguments[4].equalsIgnoreCase("facing")) {
            return dropped("summon", arguments[4], unsupported);
        }
        if (arguments.length != 4) {
            unsupported.add("command summon with a rotation, an event or a name, which Java reads "
                + "as the entity's own data instead");
            return null;
        }
        return "summon " + type + " " + String.join(" ", Arrays.copyOfRange(arguments, 1, 4));
    }

    /**
     * `damage <target> <amount> [cause] [entity <damager>]`.
     *
     * Bedrock names a cause out of its own list and Java names a damage type out of a registry, so
     * a blow lands for the amount the pack asked for by a source that names no cause. Java asks for
     * that type before it will take the entity that dealt the blow.
     */
    private static String damage(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        if (parts.length < 3) return "damage " + rest;

        unsupported.add("command damage with a cause, which Java has no damage type for");
        if (parts.length < 5 || !parts[3].equalsIgnoreCase("entity")) {
            return "damage " + parts[0] + " " + parts[1];
        }
        return "damage " + parts[0] + " " + parts[1] + " minecraft:generic by " + parts[4];
    }

    /** Bedrock searches only the chunks it has yet to generate, which Java has no form of. */
    private static String locate(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        if (parts.length <= 2) return "locate " + rest;

        unsupported.add("command locate in new chunks only, which Java searches every chunk for");
        return null;
    }

    /**
     * `loot`, whose one difference is the slot a replacement goes into.
     *
     * Bedrock names the slot and its index apart where Java writes them as one, the same way
     * `replaceitem` does. Every other form of the command is spelled alike in both.
     */
    private static String loot(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        if (!parts[0].equalsIgnoreCase("replace")) return "loot " + rest;

        int slotAt = parts.length > 1 && parts[1].equalsIgnoreCase("block") ? 5 : 3;
        if (parts.length <= slotAt + 2) {
            unsupported.add("command loot replace with nothing to put in the slot");
            return null;
        }

        String slot = javaSlot(parts[slotAt].toLowerCase(Locale.ROOT), parts[slotAt + 1]);
        if (slot == null) return dropped("loot", parts[slotAt], unsupported);

        return "loot " + String.join(" ", Arrays.copyOfRange(parts, 0, slotAt)) + " " + slot + " "
            + String.join(" ", Arrays.copyOfRange(parts, slotAt + 2, parts.length));
    }

    /**
     * `place`, of whose four kinds Java has three.
     *
     * A feature rule is Bedrock's own table of where a feature may appear, which Java reads from
     * worldgen rather than from a command. What Bedrock writes after the position says how a
     * structure or a jigsaw is built, and Java's own command decides each of those for itself.
     */
    private static String place(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        String kind = parts[0].toLowerCase(Locale.ROOT);
        int most = switch (kind) {
            case "feature", "structure" -> 5;
            case "jigsaw" -> 7;
            default -> 0;
        };

        if (most == 0) return dropped("place", kind, unsupported);
        if (parts.length <= most) return "place " + rest;

        unsupported.add("command place " + kind + " with a placement Java builds for itself");
        return null;
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
        if (run < 0) return "execute " + dimensions(rest);

        String inner = translate(rest.substring(run + " run ".length()), unsupported);
        return inner == null ? null : "execute " + dimensions(rest.substring(0, run)) + " run " + inner;
    }

    /**
     * The clauses of an `execute` with the one dimension the two editions name differently renamed.
     *
     * Java's parser reads a dimension as a resource location and finds nothing under `nether`, so a
     * line naming it would run and do nothing. The overworld and the end are spelled the same.
     */
    private static String dimensions(String clauses) {
        return NETHER_CLAUSE.matcher(clauses).replaceAll("in the_nether");
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
        String bedrock = GAME_RULES.get(parts[0].toLowerCase(Locale.ROOT));
        String java = bedrock == null ? null : Platform.gameRuleName(bedrock);
        if (java == null) {
            unsupported.add("game rule " + parts[0]);
            return null;
        }
        if (parts.length < 2) return "gamerule " + java;

        String value = gameRuleValue(bedrock, parts[1]);
        if (value == null) {
            unsupported.add("game rule " + parts[0] + " " + parts[1]);
            return null;
        }
        return "gamerule " + java + " " + value;
    }

    /**
     * What Java's own rule is set to for what a Bedrock rule was set to.
     *
     * Two rules read a word Java's does not. Bedrock names who sees a player's waypoint where Java
     * asks whether the bar is there at all, and Bedrock switches fire spreading where Java from 26
     * measures how far from a player fire spreads.
     */
    private static String gameRuleValue(String bedrock, String value) {
        if (bedrock.equals("playerWaypoints")) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "everyone" -> "true";
                case "off" -> "false";
                default -> null;
            };
        }

        Integer radius = bedrock.equals("doFireTick")
            ? Platform.fireSpreadRadius(Boolean.parseBoolean(value)) : null;
        return radius == null ? value : String.valueOf(radius);
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

        String slot = javaSlot(parts[named].toLowerCase(Locale.ROOT), parts[named + 1]);
        if (slot == null) return dropped("replaceitem", parts[named], unsupported);

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

    /**
     * The Java slot a Bedrock slot and its index name, or null when Java holds no such place.
     *
     * A Bedrock `slot.armor` index counts the four armour places the way Java names them one by
     * one, and the numbered slots take the index after a dot.
     */
    private static String javaSlot(String bedrock, String index) {
        String named = EQUIPMENT_SLOTS.get(bedrock);
        if (named != null) return NUMBERED_SLOTS.contains(named) ? named + "." + index : named;

        return switch (bedrock) {
            case "slot.armor" -> armourSlot(index);
            case "slot.saddle" -> Platform.saddleSlot();
            case "slot.armor.body" -> Platform.bodyArmourSlot();
            default -> null;
        };
    }

    /** Null for an index outside the four armour places, which Java would have nowhere to put. */
    private static String armourSlot(String index) {
        try {
            int place = Integer.parseInt(index);
            return place >= 0 && place < ARMOUR_SLOTS.size() ? ARMOUR_SLOTS.get(place) : null;
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /**
     * Bedrock rides through five modes and Java has the two that move a rider on and off.
     *
     * The three left over summon the other half of the pair as they mount it, and empty a vehicle
     * of riders Java's selectors have no way to name. Bedrock names as many riders as its selector
     * matches where Java's command takes one, so the riders are stepped through one at a time.
     */
    private static String ride(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 3);
        if (parts.length < 2) {
            unsupported.add("command ride with no mode");
            return null;
        }

        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "stop_riding" -> "execute as " + parts[0] + " run ride @s dismount";
            case "start_riding" -> startRiding(parts, unsupported);
            default -> dropped("ride", parts[1], unsupported);
        };
    }

    /**
     * `ride <riders> start_riding <ride> [teleportRules] [howToFill]`.
     *
     * Java moves the rider to what it mounts, which is what `teleport_rider` asks for. Moving the
     * ride instead, and either of the two ways of filling a vehicle that has room for several, are
     * decisions Java's own command does not offer.
     */
    private static String startRiding(String[] parts, Set<String> unsupported) {
        if (parts.length < 3) {
            unsupported.add("command ride start_riding with nothing to ride");
            return null;
        }

        String[] following = SPACE.split(parts[2]);
        for (String rule : Arrays.copyOfRange(following, 1, following.length)) {
            if (rule.equalsIgnoreCase("teleport_rider")) continue;
            return dropped("ride", rule, unsupported);
        }
        return "execute as " + parts[0] + " at @s run ride @s mount " + nearest(following[0]);
    }

    /**
     * A selector Java will take where it wants one entity, which is the nearest one that matches.
     *
     * `@s`, `@p` and `@r` already stand for one entity and Java rejects a count on them. The other
     * two match as many as they find, which is what Bedrock hands a rider and Java will not.
     */
    private static String nearest(String selector) {
        if (!selector.startsWith("@e") && !selector.startsWith("@a")) return selector;
        if (selector.contains("c=")) return selector;

        return selector.endsWith("]")
            ? selector.substring(0, selector.length() - 1) + ",c=1]" : selector + "[c=1]";
    }

    /** Bedrock holds and resumes the world write; Java turns the same write off and on. */
    private static String save(String rest, Set<String> unsupported) {
        return switch (rest.toLowerCase(Locale.ROOT)) {
            case "hold" -> "save-off";
            case "resume" -> "save-on";
            default -> dropped("save", rest, unsupported);
        };
    }

    /**
     * A Bedrock ticking area is Java's forceload, which works in whole chunks.
     *
     * Java's takes the two corners as columns, so the heights are left out. A circular area and one
     * picked out by the name it was added under have no Java form, and neither does a preload: Java
     * loads a forced chunk as soon as it is named, with nothing to ask again later.
     */
    private static String tickingArea(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        String mode = parts[0].toLowerCase(Locale.ROOT);

        if (mode.equals("remove_all")) return "forceload remove all";
        if (mode.equals("list")) {
            if (parts.length == 1) return "forceload query";
            return dropped("tickingarea", parts[1], unsupported);
        }
        if (mode.equals("remove") && parts.length >= 4) {
            return "forceload remove " + parts[1] + " " + parts[3];
        }
        if (mode.equals("add")) {
            if (parts.length > 1 && parts[1].equalsIgnoreCase("circle")) {
                return dropped("tickingarea", parts[1], unsupported);
            }
            if (parts.length >= 7) {
                return "forceload add " + parts[1] + " " + parts[3] + " " + parts[4] + " " + parts[6];
            }
        }
        return dropped("tickingarea", mode, unsupported);
    }

    /**
     * The line with its first argument quoted, which is how a name carrying a colon reaches Java.
     *
     * Brigadier reads letters, digits and a few marks as an unquoted string and stops at a colon, so
     * a pack's own `vn:song` would end the argument halfway and take the whole function with it.
     */
    private static String quotedName(String rest) {
        String[] parts = SPACE.split(rest, 2);
        return new JsonPrimitive(parts[0]) + (parts.length > 1 ? " " + parts[1] : "");
    }

    /**
     * `dialogue open <npc> <player> [scene]` and `dialogue change <npc> <scene> [players]`.
     *
     * The scene tag carries a namespace as often as not, so it is quoted wherever it stands. Which
     * word that is differs between the two forms: `open` names it last and `change` names it before
     * the players it changes the scene for.
     */
    private static String dialogue(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest.strip());
        String mode = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";

        if (mode.equals("open") && parts.length >= 3) {
            String scene = parts.length > 3 ? " " + new JsonPrimitive(parts[3]) : "";
            return "dialogue open " + parts[1] + " " + parts[2] + scene;
        }
        if (mode.equals("change") && parts.length >= 3) {
            // Bedrock's third argument narrows the change to some players, and the scene an NPC
            // shows is here a fact about the NPC rather than one about each player looking at it.
            if (parts.length > 3) unsupported.add("command dialogue change for chosen players");
            return "dialogue change " + parts[1] + " " + new JsonPrimitive(parts[2]);
        }
        return dropped("dialogue", mode, unsupported);
    }

    /**
     * `changesetting <setting> <value>`, of whose two settings Java has a command for one.
     *
     * Whether cheats are allowed is a property of the world a Java server reads at startup, with no
     * command to move it while the server runs.
     */
    private static String changeSetting(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        if (parts.length < 2 || !parts[0].equalsIgnoreCase("difficulty")) {
            return dropped("changesetting", parts[0], unsupported);
        }
        return "difficulty " + difficulty(parts[1]);
    }

    /**
     * `music play|queue <track> [volume] [fade] [repeat]`, `music stop [fade]` and `music volume`.
     *
     * A track is played as a sound in the music category, which fades in and out on its own and
     * plays through once. A repeating track and a mixer level have no command behind them.
     */
    private static String music(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        String action = parts[0].toLowerCase(Locale.ROOT);
        if (action.equals("stop")) return "music stop";
        if (parts.length < 2 || !action.equals("play") && !action.equals("queue")) {
            return dropped("music", action, unsupported);
        }

        String repeat = parts.length > 4 ? parts[4].toLowerCase(Locale.ROOT) : "play_once";
        if (!repeat.equals("play_once")) return dropped("music", repeat, unsupported);
        return "music " + action + " " + new JsonPrimitive(parts[1])
            + (parts.length > 2 ? " " + parts[2] : "");
    }

    /** Bedrock saves a region under a name, places it again and forgets it. */
    private static String structure(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest);
        return switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "save" -> saveStructure(parts, unsupported);
            case "load" -> loadStructure(parts, unsupported);
            case "delete" -> {
                if (parts.length < 2) {
                    unsupported.add("command structure delete with no name");
                    yield null;
                }
                yield "structure delete " + new JsonPrimitive(parts[1]);
            }
            default -> dropped("structure", parts[0], unsupported);
        };
    }

    /**
     * `structure save <name> <from> <to> [includeEntities] [saveMode] [includeBlocks]`.
     *
     * A Java template holds the blocks and the entities of the region either way and is written as
     * a file, which is what `disk` asks for. `memory` asks for one the world never keeps.
     */
    private static String saveStructure(String[] parts, Set<String> unsupported) {
        if (parts.length < 8) {
            unsupported.add("command structure save with no region to save");
            return null;
        }
        for (String option : Arrays.copyOfRange(parts, 8, parts.length)) {
            String word = option.toLowerCase(Locale.ROOT);
            if (word.equals("disk") || word.equals("true") || word.equals("false")) continue;
            return dropped("structure", word, unsupported);
        }
        return "structure save " + new JsonPrimitive(parts[1]) + " "
            + String.join(" ", Arrays.copyOfRange(parts, 2, 8));
    }

    /**
     * `structure load <name> <to> [rotation] [mirror] ...`, which Java places as a template.
     *
     * Java's own `place template` turns and mirrors a template as it places it, and takes the name
     * as a resource location, so the line goes to it rather than to the command registered here.
     * Everything Bedrock writes after the mirror describes a placement Java builds in its own code:
     * an animation it plays none of, and the entity, block and waterlogging choices it makes itself.
     */
    private static String loadStructure(String[] parts, Set<String> unsupported) {
        if (parts.length < 5) {
            unsupported.add("command structure load with nowhere to put it");
            return null;
        }
        StringBuilder placement = new StringBuilder("place template " + parts[1] + " "
            + String.join(" ", Arrays.copyOfRange(parts, 2, 5)));

        for (int at = 5; at < parts.length; at++) {
            String word = parts[at].toLowerCase(Locale.ROOT);
            String java = switch (at) {
                case 5 -> STRUCTURE_ROTATIONS.get(word);
                case 6 -> STRUCTURE_MIRRORS.get(word);
                default -> null;
            };
            if (java == null) return dropped("structure", word, unsupported);
            placement.append(' ').append(java);
        }
        return placement.toString();
    }

    /**
     * `schedule delay`, which is the form both editions mean the same thing by.
     *
     * Bedrock writes `schedule delay add <function> <time> <replace|append>` and Java writes
     * `schedule function <function> <time> [append|replace]`, with the same two words for the last
     * argument, so the whole of the delay half crosses by swapping one keyword. `schedule clear` is
     * spelled alike in both and `schedule delay clear` is the same thing said the longer way.
     * `on_area_loaded` waits for chunks to be loaded, which Java keeps no queue for.
     */
    private static String schedule(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        String mode = parts[0].toLowerCase(Locale.ROOT);
        String tail = parts.length > 1 ? parts[1] : "";

        if (mode.equals("clear")) return "schedule clear " + tail;
        if (!mode.equals("delay")) return dropped("schedule", mode, unsupported);

        String[] action = SPACE.split(tail, 2);
        return switch (action[0].toLowerCase(Locale.ROOT)) {
            case "add" -> action.length > 1 ? "schedule function " + action[1] : null;
            case "clear" -> action.length > 1 ? "schedule clear " + action[1] : null;
            default -> dropped("schedule", action[0].toLowerCase(Locale.ROOT), unsupported);
        };
    }

    /**
     * `mobevent <event> [enabled]`, of whose four events three reach a Java game rule.
     *
     * `events_enabled` names every event at once. The ender dragon's is whether the fight may be
     * started again, which Java decides from the state of the end itself.
     */
    private static String mobEvent(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        String event = parts[0].toLowerCase(Locale.ROOT);
        if (!event.equals("minecraft:pillager_patrols_event")
            && !event.equals("minecraft:wandering_trader_event") && !event.equals("events_enabled")) {
            return dropped("mobevent", event, unsupported);
        }
        return "mobevent " + event + (parts.length > 1 ? " " + parts[1] : "");
    }

    /**
     * The test modes the `/gametest` registered here builds.
     *
     * The rest describe the Bedrock test editor: laying out a new test area, reading a position
     * inside one, running the tests around the player, and repeating a set until one fails.
     */
    private static String gameTest(String rest, Set<String> unsupported) {
        String[] parts = SPACE.split(rest, 2);
        String mode = parts[0].toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "run", "runset", "runall", "clearall", "stopall" ->
                "gametest " + mode + (parts.length > 1 ? " " + parts[1] : "");
            default -> dropped("gametest", mode, unsupported);
        };
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

    private static Map<String, String> lowerCased(Set<String> shared, Set<String> newer) {
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        Stream.concat(shared.stream(), newer.stream())
            .forEach(name -> byLowerCase.put(name.toLowerCase(Locale.ROOT), name));
        return Map.copyOf(byLowerCase);
    }

    /**
     * Reports a form of a translated command that Java has no way to say, and drops the line.
     *
     * A form named as one Java has nothing for is reported as such, and any other form as the form
     * it is, which is how a selector argument Java cannot read is reported too.
     */
    private static String dropped(String command, String keyword, Set<String> unsupported) {
        String form = command + " " + keyword.toLowerCase(Locale.ROOT);
        unsupported.add(BEDROCK_ONLY_KEYWORDS.contains(form)
            ? "command " + form + ", which Java has no form of" : "command " + form);
        return null;
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
