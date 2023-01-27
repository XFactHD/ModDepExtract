package xfacthd.depextract.data.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import xfacthd.depextract.Main;
import xfacthd.depextract.util.Utils;

import java.util.*;
import java.util.stream.Collectors;

public enum MixinInjectionType
{
    ACCESSOR("Accessor")
    {
        private final List<String> prefixes = List.of("get", "set", "is");

        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Optional<String> annoTarget = findAnnotationValue(anno.values, "value", String.class);
            String target = annoTarget.orElseGet(() -> stripPrefix(mth.name, prefixes));
            return Map.of("field", target);
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return List.of("Target field: " + target.get("field"));
        }
    },
    INVOKER("Invoker")
    {
        private final List<String> prefixes = List.of("call", "invoke");

        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Optional<String> annoTarget = findAnnotationValue(anno.values, "value", String.class);
            String target = annoTarget.orElseGet(() -> stripPrefix(mth.name, prefixes));
            return Map.of("method", target);
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return List.of("Target method: " + target.get("method"));
        }
    },
    INJECT("Inject")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Map<String, String> map = new HashMap<>();

            Optional<List<String>> methods = findAnnotationValue(anno.values, "method", List.class);
            Optional<Boolean> cancellable = findAnnotationValue(anno.values, "cancellable", Boolean.class);
            Optional<Integer> ordinalOpt = findAnnotationValue(anno.values, "ordinal", Integer.class);

            map.put("method", methods.map(mths -> String.join(",", mths)).orElse("[INVALID MTH]"));
            map.put("target", extractAtDescriptor(anno, map));
            map.put("cancellable", cancellable.orElse(false) ? "true" : "false");
            ordinalOpt.ifPresent(ordinal -> map.put("ordinal", String.valueOf(ordinal)));
            return map;
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return target.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        }
    },
    REDIRECT("Redirect")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Map<String, String> map = new HashMap<>();

            Optional<List<String>> methods = findAnnotationValue(anno.values, "method", List.class);

            map.put("method", methods.map(mths -> String.join(",", mths)).orElse("[INVALID MTH]"));
            map.put("target", extractAtDescriptor(anno, map));
            return map;
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return target.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        }
    },
    MODIFY_ARG("ModifyArg")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Map<String, String> map = new HashMap<>();

            Optional<List<String>> methods = findAnnotationValue(anno.values, "method", List.class);

            map.put("method", methods.map(mths -> String.join(",", mths)).orElse("[INVALID MTH]"));
            map.put("target", extractAtDescriptor(anno, map));
            map.put("index", String.valueOf(findAnnotationValue(anno.values, "index", Integer.class).orElse(-1)));
            return map;
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return target.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        }
    },
    MODIFY_ARGS("ModifyArgs")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Map<String, String> map = new HashMap<>();

            Optional<List<String>> methods = findAnnotationValue(anno.values, "method", List.class);

            map.put("method", methods.map(mths -> String.join(",", mths)).orElse("[INVALID MTH]"));
            map.put("target", extractAtDescriptor(anno, map));
            return map;
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return target.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        }
    },
    MODIFY_CONSTANT("ModifyConstant")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Map<String, String> map = new HashMap<>();

            Optional<List<String>> methods = findAnnotationValue(anno.values, "method", List.class);
            Optional<Integer> ordinalOpt = findAnnotationValue(anno.values, "ordinal", Integer.class);

            Optional<List<AnnotationNode>> constOpt = findAnnotationValue(anno.values, "constant", List.class);
            String constant = "[INVALID CONST]";
            if (constOpt.isPresent())
            {
                constant = constOpt.get()
                        .stream()
                        .map(constNode ->
                        {
                            Optional<List<String>> conditions = findAnnotationValue(anno.values, "expandZeroConditions", List.class);
                            conditions.ifPresent(cond -> map.put("conditions", String.join(", ", cond)));

                            for (int i = 0; i < constNode.values.size(); i += 2)
                            {
                                String key = (String) constNode.values.get(i);
                                Object value = constNode.values.get(i + 1);
                                switch (key)
                                {
                                    case "nullValue":
                                    {
                                        if ((boolean) value)
                                        {
                                            return "Null";
                                        }
                                    }
                                    case "intValue": return "Int: " + ((int) value);
                                    case "floatValue": return "Float: " + ((float) value);
                                    case "longValue": return "Long: " + ((long) value);
                                    case "doubleValue": return "Double: " + ((double) value);
                                    case "stringValue": return "String: " + value;
                                    case "classValue": return "Class: " + ((Type) value).getClassName();
                                }
                            }
                            return null;
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", "));
            }

            map.put("method", methods.map(mths -> String.join(",", mths)).orElse("[INVALID MTH]"));
            map.put("constant", constant);
            ordinalOpt.ifPresent(ordinal -> map.put("ordinal", String.valueOf(ordinal)));
            return map;
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return target.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        }
    },
    MODIFY_VARIABLE("ModifyVariable")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Map<String, String> map = new HashMap<>();

            Optional<List<String>> methods = findAnnotationValue(anno.values, "method", List.class);
            Optional<Integer> ordinalOpt = findAnnotationValue(anno.values, "ordinal", Integer.class);
            Optional<Integer> indexOpt = findAnnotationValue(anno.values, "index", Integer.class);

            map.put("method", methods.map(mths -> String.join(",", mths)).orElse("[INVALID MTH]"));
            map.put("target", extractAtDescriptor(anno, map));
            ordinalOpt.ifPresent(ordinal -> map.put("ordinal", String.valueOf(ordinal)));
            indexOpt.ifPresent(index -> map.put("index", String.valueOf(index)));
            return map;
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return target.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
        }
    },
    OVERWRITE("Overwrite")
    {
        @Override
        public Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            return Map.of();
        }

        @Override
        public List<String> printTarget(Map<String, String> target)
        {
            return List.of();
        }
    };

    private final String name;

    MixinInjectionType(String name)
    {
        this.name = name;
    }

    public abstract Map<String, String> parseAnnotationData(MethodNode mth, AnnotationNode anno);

    public abstract List<String> printTarget(Map<String, String> target);

    @Override
    public String toString()
    {
        return name;
    }



    private static <T, R extends T> Optional<R> findAnnotationValue(List<Object> values, String name, Class<T> type)
    {
        if (values == null || values.isEmpty())
        {
            return Optional.empty();
        }

        for (int i = 0; i < values.size(); i += 2)
        {
            if (values.get(i).equals(name))
            {
                Object value = values.get(i + 1);
                if (type.isAssignableFrom(value.getClass()))
                {
                    //noinspection unchecked
                    return Optional.of((R) value);
                }
            }
        }
        return Optional.empty();
    }

    private static String stripPrefix(String value, List<String> prefixes)
    {
        for (String pre : prefixes)
        {
            if (value.startsWith(pre))
            {
                int len = pre.length();
                return Utils.toFirstCharLower(value.substring(len));
            }
        }
        return value;
    }

    private static String extractAtDescriptor(AnnotationNode anno, Map<String, String> map)
    {
        Optional<List<AnnotationNode>> atNodeOpt = findAnnotationValue(anno.values, "at", List.class);
        if (atNodeOpt.isEmpty())
        {
            // Certain injectors (ModifyArg, ModifyArgs, ModifyVariable and Redirect) use a single @At specifiers
            atNodeOpt = findAnnotationValue(anno.values, "at", AnnotationNode.class).map(List::of);
        }

        String atDesc = "[INVALID AT]";
        if (atNodeOpt.isPresent())
        {
            atDesc = atNodeOpt.get()
                    .stream()
                    .map(atNode ->
                    {
                        Optional<String> typeOpt = findAnnotationValue(atNode.values, "value", String.class);
                        return typeOpt.map(type -> switch (type)
                        {
                            case "HEAD" -> "Head";
                            case "TAIL" -> "Tail";
                            case "RETURN" -> "Return";
                            case "INVOKE" ->
                            {
                                Optional<String> target = findAnnotationValue(atNode.values, "target", String.class);
                                String result = "Invoke " + target.orElse("[INVALID TARGET]");
                                yield extractShift(atNode, result);
                            }
                            case "INVOKE_ASSIGN" ->
                            {
                                Optional<String> target = findAnnotationValue(atNode.values, "target", String.class);
                                String result = "InvokeAssign " + target.orElse("[INVALID TARGET]");
                                yield extractShift(atNode, result);
                            }
                            case "FIELD" ->
                            {
                                Optional<String> target = findAnnotationValue(atNode.values, "target", String.class);
                                String result = "Field " + target.orElse("[INVALID TARGET]");

                                Optional<Integer> opcode = findAnnotationValue(atNode.values, "opcode", Integer.class);
                                if (opcode.isPresent())
                                {
                                    String opName = switch (opcode.get())
                                    {
                                        case Opcodes.GETFIELD -> "GetField";
                                        case Opcodes.PUTFIELD -> "PutField";
                                        case Opcodes.GETSTATIC -> "GetStatic";
                                        case Opcodes.PUTSTATIC -> "PutStatic";
                                        default -> "[INVALID TARGET]";
                                    };
                                    map.put("opcode", opName);
                                }

                                Optional<List<String>> args = findAnnotationValue(atNode.values, "args", List.class);
                                if (args.isPresent())
                                {
                                    for (String entry : args.get())
                                    {
                                        if (entry.startsWith("array="))
                                        {
                                            map.put("array_op", entry.substring(6));
                                            break;
                                        }
                                    }
                                }

                                yield extractShift(atNode, result);
                            }
                            case "NEW" ->
                            {
                                Optional<String> target = findAnnotationValue(atNode.values, "target", String.class);
                                Optional<List<String>> args = findAnnotationValue(atNode.values, "args", List.class);
                                if (target.isEmpty() && args.isPresent())
                                {
                                    for (String entry : args.get())
                                    {
                                        if (entry.startsWith("class="))
                                        {
                                            target = Optional.of(entry.substring(6));
                                            break;
                                        }
                                    }
                                }
                                String result = "New " + target.orElse("[INVALID TARGET]");
                                yield extractShift(atNode, result);
                            }
                            case "INVOKE_STRING" ->
                            {
                                Optional<String> target = findAnnotationValue(atNode.values, "target", String.class);
                                String result = "InvokeString " + target.orElse("[INVALID TARGET]");

                                Optional<List<String>> args = findAnnotationValue(atNode.values, "args", List.class);
                                if (args.isPresent())
                                {
                                    for (String entry : args.get())
                                    {
                                        if (entry.startsWith("ldc="))
                                        {
                                            map.put("string", entry.substring(4));
                                            break;
                                        }
                                    }
                                }
                                yield extractShift(atNode, result);
                            }
                            case "JUMP" ->
                            {
                                Optional<Integer> opcode = findAnnotationValue(atNode.values, "opcode", Integer.class);
                                if (opcode.isPresent())
                                {
                                    String opName = switch (opcode.get())
                                    {
                                        case Opcodes.IFEQ -> "IfEqual";
                                        case Opcodes.IFNE -> "IfNotEqual";
                                        case Opcodes.IFLT -> "IfLessThan";
                                        case Opcodes.IFGE -> "IfGreaterOrEqual";
                                        case Opcodes.IFGT -> "IfGreaterThan";
                                        case Opcodes.IFLE -> "IfLessOrEqual";
                                        case Opcodes.IF_ICMPEQ -> "IfIntEqual";
                                        case Opcodes.IF_ICMPNE -> "IfIntNotEqual";
                                        case Opcodes.IF_ICMPLT -> "IfIntLessThan";
                                        case Opcodes.IF_ICMPGE -> "IfIntGreaterOrEqual";
                                        case Opcodes.IF_ICMPGT -> "IfIntGreaterThan";
                                        case Opcodes.IF_ICMPLE -> "IfIntLessOrEqual";
                                        case Opcodes.IF_ACMPEQ -> "IfRefEqual";
                                        case Opcodes.IF_ACMPNE -> "IfRefNotEqual";
                                        case Opcodes.GOTO -> "Goto";
                                        case Opcodes.JSR -> "JumpSubroutine";
                                        case Opcodes.IFNULL -> "IfNull";
                                        case Opcodes.IFNONNULL -> "IfNonNull";
                                        case -1 -> "Any Jump";
                                        default -> "[INVALID OP]";
                                    };
                                    map.put("opcode", opName);
                                }
                                yield extractShift(atNode, "Jump");
                            }
                            case "CONSTANT" ->
                            {
                                Optional<List<String>> args = findAnnotationValue(atNode.values, "args", List.class);
                                if (args.isPresent())
                                {
                                    for (String entry : args.get())
                                    {
                                        String[] parts = entry.split("=");
                                        if (parts.length == 2)
                                        {
                                            map.put(parts[0], parts[1]);
                                        }
                                    }
                                }
                                yield extractShift(atNode, "Constant");
                            }
                            case "LOAD" ->
                            {
                                Optional<Integer> ordinalOpt = findAnnotationValue(atNode.values, "ordinal", Integer.class);
                                ordinalOpt.ifPresent(ordinal -> map.put("ordinal", String.valueOf(ordinal)));
                                yield extractShift(atNode, "LoadLocal");
                            }
                            case "STORE" ->
                            {
                                Optional<Integer> ordinalOpt = findAnnotationValue(atNode.values, "ordinal", Integer.class);
                                ordinalOpt.ifPresent(ordinal -> map.put("ordinal", String.valueOf(ordinal)));
                                yield extractShift(atNode, "StoreLocal");
                            }
                            default ->
                            {
                                Main.LOG.error("Unknown injection point type: " + type);
                                yield "[UNKNOWN] " + type;
                            }
                        }).orElse(null);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
        }
        return atDesc;
    }

    private static String extractShift(AnnotationNode atNode, String atDesc)
    {
        Optional<String> shift = findAnnotationValue(atNode.values, "shift", String.class);
        if (shift.isPresent() && !shift.get().equals("NONE"))
        {
            String result = switch (shift.get())
            {
                case "BEFORE" -> "(shifted before)";
                case "AFTER" -> "(shifted after)";
                case "BY" ->
                {
                    Optional<Integer> count = findAnnotationValue(atNode.values, "by", Integer.class);
                    yield "(shifted by " + count.orElse(0) + " opcodes)";
                }
                default -> "[INVALID SHIFT]";
            };
            return atDesc + " " + result;
        }
        return atDesc;
    }
}
