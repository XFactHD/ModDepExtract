package xfacthd.depextract.data.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import xfacthd.depextract.Main;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.html.HtmlWriter;
import xfacthd.depextract.util.*;

import java.util.*;

public enum MixinAtType
{
    HEAD
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            return new MixinAtDescriptor(HEAD, Map.of());
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Head");
        }
    },
    TAIL
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            return new MixinAtDescriptor(TAIL, Map.of());
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Tail");
        }
    },
    RETURN
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            return new MixinAtDescriptor(RETURN, Map.of());
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Return");
        }
    },
    INVOKE
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<String> target = Utils.findAnnotationValue(anno.values, "target", String.class);
            return new MixinAtDescriptor(INVOKE, Map.of(
                    "target", target.orElse(""),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Invoke ");
            printStandardTarget(writer, atDesc);
        }
    },
    INVOKE_ASSIGN
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<String> target = Utils.findAnnotationValue(anno.values, "target", String.class);
            return new MixinAtDescriptor(INVOKE_ASSIGN, Map.of(
                    "target", target.orElse(""),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("InvokeAssign ");
            printStandardTarget(writer, atDesc);
        }
    },
    INVOKE_STRING
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<String> target = Utils.findAnnotationValue(anno.values, "target", String.class);
            Optional<List<String>> args = Utils.findAnnotationValue(anno.values, "args", List.class);
            Optional<String> stringConst = args.flatMap(list -> list.stream()
                    .filter(entry -> entry.startsWith("ldc="))
                    .map(entry -> entry.substring(4))
                    .findFirst()
            );
            return new MixinAtDescriptor(INVOKE_STRING, Map.of(
                    "target", target.orElse(""),
                    "shift", extractShift(anno),
                    "string", stringConst.orElse("")
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("InvokeString ");
            printStandardTarget(writer, atDesc);
        }

        @Override
        public void printAdditionalDetails(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            String stringConst = atDesc.params().get("string");
            if (!stringConst.isEmpty())
            {
                Html.listEntry(writer, inner ->
                {
                    writer.print("String constant: ");
                    Html.span(inner, "style=\"color: #698650;\"", "\"" + stringConst + "\"");
                });
            }
        }
    },
    FIELD
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<String> target = Utils.findAnnotationValue(anno.values, "target", String.class);

            String opName = "";
            Optional<Integer> opcode = Utils.findAnnotationValue(anno.values, "opcode", Integer.class);
            if (opcode.isPresent())
            {
                opName = switch (opcode.get())
                {
                    case Opcodes.GETFIELD -> "GetField";
                    case Opcodes.PUTFIELD -> "PutField";
                    case Opcodes.GETSTATIC -> "GetStatic";
                    case Opcodes.PUTSTATIC -> "PutStatic";
                    default -> "[INVALID OPCODE]";
                };
            }

            Optional<List<String>> args = Utils.findAnnotationValue(anno.values, "args", List.class);
            Optional<String> arrayOp = args.flatMap(list -> list.stream()
                    .filter(entry -> entry.startsWith("array="))
                    .map(entry -> entry.substring(4))
                    .findFirst()
            );

            return new MixinAtDescriptor(FIELD, Map.of(
                    "target", target.orElse(""),
                    "opcode", opName,
                    "array_op", arrayOp.orElse(""),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Field ");
            printStandardTarget(writer, atDesc);
        }

        @Override
        public void printAdditionalDetails(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            String opcode = atDesc.params().get("opcode");
            if (!opcode.isEmpty())
            {
                Html.listEntry(writer, inner ->
                {
                    writer.print("Opcode: ");
                    writer.print(opcode);
                });
            }

            String arrayOp = atDesc.params().get("array_op");
            if (!arrayOp.isEmpty())
            {
                Html.listEntry(writer, inner ->
                {
                    writer.print("Array operation: ");
                    writer.print(arrayOp);
                });
            }
        }
    },
    NEW
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<String> target = Utils.findAnnotationValue(anno.values, "target", String.class);
            Optional<List<String>> args = Utils.findAnnotationValue(anno.values, "args", List.class);
            if (target.isEmpty() && args.isPresent())
            {
                target = args.flatMap(list -> list.stream()
                        .filter(entry -> entry.startsWith("class="))
                        .map(entry -> entry.substring(4))
                        .findFirst()
                );
            }
            return new MixinAtDescriptor(NEW, Map.of(
                    "target", target.orElse(""),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Field ");
            printStandardTarget(writer, atDesc);
        }
    },
    JUMP
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            String opName = "[INVALID OP]";
            Optional<Integer> opcode = Utils.findAnnotationValue(anno.values, "opcode", Integer.class);
            if (opcode.isPresent())
            {
                opName = switch (opcode.get())
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
            }

            return new MixinAtDescriptor(JUMP, Map.of(
                    "opcode", opName,
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Jump " + atDesc.params().get("opcode"));

            String shift = atDesc.params().get("shift");
            if (!shift.isEmpty())
            {
                writer.print(" " + shift);
            }
        }
    },
    CONSTANT
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            List<String> constants = new ArrayList<>();
            Optional<List<String>> args = Utils.findAnnotationValue(anno.values, "args", List.class);
            if (args.isPresent())
            {
                for (String entry : args.get())
                {
                    String[] parts = entry.split("=");
                    if (parts.length == 2)
                    {
                        String value = parts[1];
                        constants.add(switch (parts[0])
                        {
                            case "nullValue" -> "Null";
                            case "intValue" -> "Int: " + value;
                            case "floatValue" -> "Float: " + value;
                            case "longValue" -> "Long: " + value;
                            case "doubleValue" -> "Double: " + value;
                            case "stringValue" -> "String: " + value;
                            case "classValue" -> "Class: " + value;
                            default -> "";
                        });
                    }
                }
                constants.removeIf(String::isEmpty);
            }

            return new MixinAtDescriptor(CONSTANT, Map.of(
                    "constants", String.join(", ", constants),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("Constant: " + atDesc.params().get("constant"));

            String shift = atDesc.params().get("shift");
            if (!shift.isEmpty())
            {
                writer.print(" " + shift);
            }
        }
    },
    LOAD
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<Integer> ordinalOpt = Utils.findAnnotationValue(anno.values, "ordinal", Integer.class);
            return new MixinAtDescriptor(LOAD, Map.of(
                    "ordinal", ordinalOpt.map(String::valueOf).orElse(""),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("LoadLocal");

            String shift = atDesc.params().get("shift");
            if (!shift.isEmpty())
            {
                writer.print(" " + shift);
            }
        }
    },
    STORE
    {
        @Override
        public MixinAtDescriptor parseAnnotationData(AnnotationNode anno)
        {
            Optional<Integer> ordinalOpt = Utils.findAnnotationValue(anno.values, "ordinal", Integer.class);
            return new MixinAtDescriptor(STORE, Map.of(
                    "ordinal", ordinalOpt.map(String::valueOf).orElse(""),
                    "shift", extractShift(anno)
            ));
        }

        @Override
        public void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
        {
            writer.print("StoreLocal");

            String shift = atDesc.params().get("shift");
            if (!shift.isEmpty())
            {
                writer.print(" " + shift);
            }
        }
    };

    public abstract MixinAtDescriptor parseAnnotationData(AnnotationNode anno);

    public abstract void printTarget(HtmlWriter writer, MixinAtDescriptor atDesc);

    public void printAdditionalDetails(HtmlWriter writer, MixinAtDescriptor atDesc) { }



    public static List<MixinAtDescriptor> parse(AnnotationNode anno)
    {
        Optional<List<AnnotationNode>> atNodeOpt = Utils.findAnnotationValue(anno.values, "at", List.class);
        if (atNodeOpt.isEmpty())
        {
            // Certain injectors (ModifyArg, ModifyArgs, ModifyVariable and Redirect) use a single @At specifier
            atNodeOpt = Utils.findAnnotationValue(anno.values, "at", AnnotationNode.class).map(List::of);
        }

        List<MixinAtDescriptor> atDesc = List.of();
        if (atNodeOpt.isPresent())
        {
            atDesc = atNodeOpt.get()
                    .stream()
                    .map(atNode ->
                    {
                        Optional<String> value = Utils.findAnnotationValue(atNode.values, "value", String.class);
                        if (value.isEmpty())
                        {
                            return null;
                        }

                        try
                        {
                            return valueOf(value.get()).parseAnnotationData(atNode);
                        }
                        catch (IllegalArgumentException e)
                        {
                            Main.LOG.error("Unknown injection point type: " + value.get());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
        return atDesc;
    }

    private static String extractShift(AnnotationNode atNode)
    {
        Optional<String> shift = Utils.findAnnotationValue(atNode.values, "shift", String.class);
        if (shift.isPresent() && !shift.get().equals("NONE"))
        {
            return switch (shift.get())
            {
                case "BEFORE" -> "(shifted before)";
                case "AFTER" -> "(shifted after)";
                case "BY" ->
                {
                    Optional<Integer> count = Utils.findAnnotationValue(atNode.values, "by", Integer.class);
                    yield "(shifted by " + count.orElse(0) + " opcodes)";
                }
                default -> "[INVALID SHIFT]";
            };
        }
        return "";
    }

    private static void printStandardTarget(HtmlWriter writer, MixinAtDescriptor atDesc)
    {
        String target = atDesc.params().get("target");
        if (!target.isEmpty())
        {
            // Gracefully handles field names
            Descriptor desc = Utils.splitMethodDescriptor(target, null);
            Utils.printDescriptor(writer, null, desc.clazz(), desc.method(), desc.descriptor());
        }
        else
        {
            writer.print("[INVALID TARGET]");
        }

        String shift = atDesc.params().get("shift");
        if (!shift.isEmpty())
        {
            writer.print(" " + shift);
        }
    }
}
