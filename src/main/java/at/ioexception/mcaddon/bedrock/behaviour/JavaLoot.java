package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Where a Java loot table names a function's or a condition's type, and where it holds each list.
 *
 * From 26.3 both types sit under `type`, the functions of an entry, a pool or a table sit under
 * `modifier`, and a holder takes one condition under `condition`, so several become the terms of an
 * `all_of`. Before 26.3 a function names its type under `function`, a condition under `condition`,
 * and the lists sit under `functions` and `conditions`.
 */
public final class JavaLoot {

    //? if >=26.3 {
    /*private static final String FUNCTION_TYPE = "type";
    private static final String CONDITION_TYPE = "type";
    private static final String FUNCTIONS = "modifier";
    *///?} else {
    private static final String FUNCTION_TYPE = "function";
    private static final String CONDITION_TYPE = "condition";
    private static final String FUNCTIONS = "functions";
    //?}

    private JavaLoot() {
    }

    public static JsonObject function(String type) {
        JsonObject function = new JsonObject();
        function.addProperty(FUNCTION_TYPE, type);
        return function;
    }

    public static JsonObject condition(String type) {
        JsonObject condition = new JsonObject();
        condition.addProperty(CONDITION_TYPE, type);
        return condition;
    }

    /** Gives the holder the functions, or leaves it without any when the list is empty. */
    public static void functions(JsonObject holder, JsonArray functions) {
        if (!functions.isEmpty()) holder.add(FUNCTIONS, functions);
    }

    /** Gives the holder the conditions, all of which have to hold, or none when the list is empty. */
    public static void conditions(JsonObject holder, JsonArray conditions) {
        if (conditions.isEmpty()) return;
        //? if >=26.3 {
        /*if (conditions.size() == 1) {
            holder.add("condition", conditions.get(0));
            return;
        }
        JsonObject all = condition("minecraft:all_of");
        all.add("terms", conditions);
        holder.add("condition", all);
        *///?} else {
        holder.add("conditions", conditions);
        //?}
    }
}
