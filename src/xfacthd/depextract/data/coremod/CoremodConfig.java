package xfacthd.depextract.data.coremod;

import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public record CoremodConfig(Map<String, String> coremods, Map<String, Boolean> jsPresent)
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
                        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight)),
                new HashMap<>()
        );
    }
}
