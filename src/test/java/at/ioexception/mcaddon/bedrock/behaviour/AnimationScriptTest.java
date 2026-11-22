package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationScriptTest {
    @Test
    void keepsEveryEntryOfAnAnimateObjectWithSeveralKeys() {
        AnimationScript script = AnimationScript.read(Json.parse("""
            {"scripts": {"animate": [{"first": "q.is_baby", "second": "!q.is_baby"}]}}
            """).getAsJsonObject());

        assertEquals(List.of("first", "second"), script.animate().stream().map(AnimationScript.Gated::name).toList());
    }
}
