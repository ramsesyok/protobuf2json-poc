package demo.json;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** JSON 構造の検証ヘルパー(変換器とは独立に、キー名ベースで検証する)。 */
final class JsonAssertions {

    /** simlog.proto 内の int64 フィールドの JSON 名(lowerCamelCase)。 */
    static final Set<String> INT64_KEYS = Set.of(
            "jobId", "timestamp", "objectId", "eventId", "eventType",
            "exitCode", "fromId", "toId", "execId");

    /** simlog.proto 内の string フィールドの JSON 名。 */
    static final Set<String> STRING_KEYS = Set.of("output", "body", "command", "result");

    private JsonAssertions() {
    }

    /**
     * ツリー全体を走査し、int64 キーの値がすべて整数値、string キーの値がすべて文字列であることを確認する。
     * ("result" は Result メッセージ(object)と ExecEvent.result(string)の 2 通りがあるので object は除外)
     *
     * @return 確認した int64 値の個数
     */
    static int assertInt64AreNumbersAndStringsAreStrings(JsonNode node, String path) {
        int count = 0;
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String p = path + "." + e.getKey();
                JsonNode v = e.getValue();
                if (INT64_KEYS.contains(e.getKey())) {
                    assertTrue(v.isIntegralNumber(), "int64 must be a JSON number: " + p + " = " + v);
                    assertTrue(v.canConvertToLong(), "int64 must fit in long: " + p + " = " + v);
                    count++;
                } else if (STRING_KEYS.contains(e.getKey()) && !v.isObject()) {
                    assertTrue(v.isTextual(), "string must stay a JSON string: " + p + " = " + v);
                }
                count += assertInt64AreNumbersAndStringsAreStrings(v, p);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                count += assertInt64AreNumbersAndStringsAreStrings(node.get(i), path + "[" + i + "]");
            }
        }
        return count;
    }

    /**
     * 素の JsonFormat 出力(raw)と変換後(converted)を並行に走査し、
     * 「int64 キーの値が "123"(文字列)→ 123(数値)になっている」以外の差分が無いことを確認する。
     * オブジェクトのキー順序も一致することを確認する。
     *
     * @return 文字列→数値になった箇所の個数
     */
    static int assertSameExceptInt64Quotes(JsonNode raw, JsonNode converted, String path) {
        if (raw.isObject()) {
            assertTrue(converted.isObject(), path);
            List<String> rawKeys = new ArrayList<>();
            raw.fieldNames().forEachRemaining(rawKeys::add);
            List<String> convKeys = new ArrayList<>();
            converted.fieldNames().forEachRemaining(convKeys::add);
            assertEquals(rawKeys, convKeys, "key order/set differs at " + path);
            int n = 0;
            for (String k : rawKeys) {
                JsonNode r = raw.get(k);
                JsonNode c = converted.get(k);
                if (INT64_KEYS.contains(k) && r.isTextual()) {
                    assertTrue(c.isIntegralNumber(), path + "." + k + " should be number: " + c);
                    assertEquals(r.textValue(), c.asText(), path + "." + k + " value changed");
                    assertEquals(Long.parseLong(r.textValue()), c.longValue(), path + "." + k);
                    n++;
                } else {
                    n += assertSameExceptInt64Quotes(r, c, path + "." + k);
                }
            }
            return n;
        }
        if (raw.isArray()) {
            assertTrue(converted.isArray(), path);
            assertEquals(raw.size(), converted.size(), path);
            int n = 0;
            for (int i = 0; i < raw.size(); i++) {
                n += assertSameExceptInt64Quotes(raw.get(i), converted.get(i), path + "[" + i + "]");
            }
            return n;
        }
        if (!raw.equals(converted)) {
            fail("value differs at " + path + ": raw=" + raw + " converted=" + converted);
        }
        return 0;
    }
}
