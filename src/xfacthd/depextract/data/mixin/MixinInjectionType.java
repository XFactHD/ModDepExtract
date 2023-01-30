package xfacthd.depextract.data.mixin;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.html.HtmlWriter;
import xfacthd.depextract.util.*;

import java.util.*;
import java.util.stream.Collectors;

public enum MixinInjectionType
{
    ACCESSOR("Accessor")
    {
        private final List<String> prefixes = List.of("get", "set", "is");

        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Optional<String> annoTarget = Utils.findAnnotationValue(anno.values, "value", String.class);
            String target = annoTarget.orElseGet(() -> stripPrefix(mth.name, prefixes));
            return new MixinTargetDescriptor(Map.of("field", target));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list -> Html.listEntry(list, entry ->
            {
                entry.print("Field: ");
                Utils.printDescriptor(entry, null, null, target.get("field"), null);
            }));
        }
    },
    INVOKER("Invoker")
    {
        private final List<String> prefixes = List.of("call", "invoke");

        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            Optional<String> annoTarget = Utils.findAnnotationValue(anno.values, "value", String.class);
            String target = annoTarget.orElseGet(() -> stripPrefix(mth.name, prefixes));
            return new MixinTargetDescriptor(Map.of(
                    "method", target,
                    "desc", mth.desc
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list -> Html.listEntry(list, entry ->
            {
                entry.print("Method: ");

                Descriptor desc = Utils.splitMethodDescriptor(target.get("method"), target.get("desc"));
                Utils.printDescriptor(entry, null, desc.clazz(), desc.method(), desc.descriptor());
            }));
        }
    },
    INJECT("Inject")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            MixinTargetDescriptor desc = new MixinTargetDescriptor();

            Optional<List<String>> methods = Utils.findAnnotationValue(anno.values, "method", List.class);
            Optional<Boolean> cancellable = Utils.findAnnotationValue(anno.values, "cancellable", Boolean.class);
            Optional<Integer> ordinalOpt = Utils.findAnnotationValue(anno.values, "ordinal", Integer.class);

            desc.put("method", methods.orElse(List.of()));
            desc.put("target", MixinAtType.parse(anno));
            desc.put("cancellable", cancellable.orElse(false));
            ordinalOpt.ifPresent(ordinal -> desc.put("ordinal", String.valueOf(ordinal)));
            return desc;
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list ->
            {
                printStandardTarget(writer, target);

                Html.listEntry(list, entry ->
                {
                    entry.print("Cancellable: ");
                    Html.writeBoolean(entry, "", target.get("cancellable"));
                });

                String ordinal = target.get("ordinal");
                if (ordinal != null)
                {
                    Html.listEntry(list, entry -> entry.print("Ordinal: " + ordinal));
                }
            });
        }
    },
    REDIRECT("Redirect")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            MixinTargetDescriptor desc = new MixinTargetDescriptor();

            Optional<List<String>> methods = Utils.findAnnotationValue(anno.values, "method", List.class);

            desc.put("method", methods.orElse(List.of()));
            desc.put("target", MixinAtType.parse(anno));
            return desc;
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list ->
                    printStandardTarget(writer, target)
            );
        }
    },
    MODIFY_ARG("ModifyArg")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            MixinTargetDescriptor desc = new MixinTargetDescriptor();

            Optional<List<String>> methods = Utils.findAnnotationValue(anno.values, "method", List.class);

            desc.put("method", methods.orElse(List.of()));
            desc.put("target", MixinAtType.parse(anno));
            desc.put("index", String.valueOf(Utils.findAnnotationValue(anno.values, "index", Integer.class).orElse(-1)));
            return desc;
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list ->
            {
                printStandardTarget(writer, target);

                Html.listEntry(list, entry ->
                {
                    String index = target.get("index");
                    entry.print("Index: " + index);
                });
            });
        }
    },
    MODIFY_ARGS("ModifyArgs")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            MixinTargetDescriptor desc = new MixinTargetDescriptor();

            Optional<List<String>> methods = Utils.findAnnotationValue(anno.values, "method", List.class);

            desc.put("method", methods.orElse(List.of()));
            desc.put("target", MixinAtType.parse(anno));
            return desc;
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list ->
                    printStandardTarget(writer, target)
            );
        }
    },
    MODIFY_CONSTANT("ModifyConstant")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            MixinTargetDescriptor desc = new MixinTargetDescriptor();

            Optional<List<String>> methods = Utils.findAnnotationValue(anno.values, "method", List.class);
            Optional<Integer> ordinalOpt = Utils.findAnnotationValue(anno.values, "ordinal", Integer.class);

            Optional<List<AnnotationNode>> constOpt = Utils.findAnnotationValue(anno.values, "constant", List.class);
            String constant = "[INVALID CONST]";
            if (constOpt.isPresent())
            {
                constant = constOpt.get()
                        .stream()
                        .map(constNode ->
                        {
                            Optional<List<String>> conditions = Utils.findAnnotationValue(anno.values, "expandZeroConditions", List.class);
                            conditions.ifPresent(cond -> desc.put("conditions", String.join(", ", cond)));

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

            desc.put("method", methods.orElse(List.of()));
            desc.put("constant", constant);
            ordinalOpt.ifPresent(ordinal -> desc.put("ordinal", String.valueOf(ordinal)));
            return desc;
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list ->
            {
                printMethods(list, target);

                String constant = target.get("constant");
                Html.listEntry(list, entry -> entry.print("Constant: " + constant));

                String ordinal = target.get("ordinal");
                if (ordinal != null)
                {
                    Html.listEntry(list, entry -> entry.print("Ordinal: " + ordinal));
                }
            });
        }
    },
    MODIFY_VARIABLE("ModifyVariable")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            MixinTargetDescriptor desc = new MixinTargetDescriptor();

            Optional<List<String>> methods = Utils.findAnnotationValue(anno.values, "method", List.class);
            Optional<Integer> ordinalOpt = Utils.findAnnotationValue(anno.values, "ordinal", Integer.class);
            Optional<Integer> indexOpt = Utils.findAnnotationValue(anno.values, "index", Integer.class);

            desc.put("method", methods.orElse(List.of()));
            desc.put("target", MixinAtType.parse(anno));
            ordinalOpt.ifPresent(ordinal -> desc.put("ordinal", String.valueOf(ordinal)));
            indexOpt.ifPresent(index -> desc.put("index", String.valueOf(index)));
            return desc;
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target)
        {
            Html.unorderedList(writer, "class=\"no_y_margin\"", list ->
            {
                printStandardTarget(list, target);

                String ordinal = target.get("ordinal");
                if (ordinal != null)
                {
                    Html.listEntry(list, entry -> entry.print("Ordinal: " + ordinal));
                }

                String index = target.get("index");
                if (index != null)
                {
                    Html.listEntry(list, entry -> entry.print("Index: " + index));
                }
            });
        }
    },
    OVERWRITE("Overwrite")
    {
        @Override
        public MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno)
        {
            return new MixinTargetDescriptor();
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinTargetDescriptor target) { }
    };

    private final String name;

    MixinInjectionType(String name)
    {
        this.name = name;
    }

    public abstract MixinTargetDescriptor parseAnnotationData(MethodNode mth, AnnotationNode anno);

    public abstract void printTarget(HtmlWriter writer, MixinTargetDescriptor target);

    @Override
    public String toString()
    {
        return name;
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

    private static void printStandardTarget(HtmlWriter writer, MixinTargetDescriptor target)
    {
        printMethods(writer, target);

        List<MixinAtDescriptor> descriptors = target.get("target");
        if (descriptors.isEmpty())
        {
            Html.listEntry(writer, entry ->
            {
                writer.print("Target: ");
                Html.span(entry, Html.getBoolColor(false), "[INVALID TARGET]");
            });
        }
        else
        {
            descriptors.forEach(desc ->
            {
                Html.listEntry(writer, entry ->
                {
                    entry.print("Target: ");
                    desc.type().printTarget(writer, desc);
                });
                desc.type().printAdditionalDetails(writer, desc);
            });
        }
    }

    private static void printMethods(HtmlWriter writer, MixinTargetDescriptor target)
    {
        List<String> methods = target.get("method");
        if (methods.isEmpty())
        {
            Html.listEntry(writer, entry ->
            {
                writer.print("Method: ");
                Html.span(entry, Html.getBoolColor(false), "[INVALID METHOD]");
            });
        }
        else
        {
            methods.forEach(mth -> Html.listEntry(writer, entry ->
            {
                writer.print("Method: ");

                Descriptor desc = Utils.splitMethodDescriptor(mth, null);
                Utils.printDescriptor(writer, null, desc.clazz(), desc.method(), desc.descriptor());
            }));
        }
    }
}
