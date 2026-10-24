package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class FilterParseTest {
    @Test
    void aFilterElementParsesOnceAcrossChecks() {
        var filters = JsonParser.parseString(
            "{\"all_of\":[{\"test\":\"is_daytime\",\"value\":true},{\"test\":\"is_underwater\"}]}");

        assertSame(Filter.parse(filters), Filter.parse(filters));
    }

    @Test
    void twoEqualElementsKeepTheirOwnFilters() {
        String text = "{\"test\":\"is_daytime\",\"value\":true}";

        assertNotSame(Filter.parse(JsonParser.parseString(text)), Filter.parse(JsonParser.parseString(text)));
    }
}
