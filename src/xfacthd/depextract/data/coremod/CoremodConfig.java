package xfacthd.depextract.data.coremod;

import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;
import java.util.stream.Collectors;

public record CoremodConfig(Map<String, Pair<String, String>> coremods)
{
    public static CoremodConfig fromJson(JsonObject obj)
    {
        return new CoremodConfig(
                obj.entrySet()
                        .stream()
                        .map(entry -> Pair.of(
                                entry.getKey(),
                                entry.getValue().getAsString())
                        )
                        .collect(Collectors.toMap(Pair::getLeft, pair -> pair))
        );
    }
}
