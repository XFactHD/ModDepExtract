package xfacthd.depextract.data.accesstransformer;

import joptsimple.ValueConverter;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public enum ChartType
{
    NONE("", t -> false),
    CLASS("Class", AccessTransformer.Target.CLASS::equals),
    METHOD("Method", AccessTransformer.Target.METHOD::equals),
    FIELD("Field", AccessTransformer.Target.FIELD::equals),
    ALL("", t -> true);

    private final String titlePrefix;
    private final Predicate<AccessTransformer.Target> typeFilter;

    ChartType(String titlePrefix, Predicate<AccessTransformer.Target> typeFilter)
    {
        this.titlePrefix = titlePrefix;
        this.typeFilter = typeFilter;
    }



    public static final class Compound
    {
        public static final Compound NONE = new Compound(EnumSet.of(ChartType.NONE));

        private final boolean active;
        private final String titlePrefix;
        private final Predicate<AccessTransformer.Target> typeFilter;

        private Compound(Set<ChartType> types)
        {
            this.active = !types.isEmpty() && !types.contains(ChartType.NONE);
            this.titlePrefix = types.stream().map(t -> t.titlePrefix).collect(Collector.of(
                    () -> new StringJoiner(", ", "", " ").setEmptyValue(""),
                    StringJoiner::add,
                    StringJoiner::merge,
                    StringJoiner::toString
            ));
            this.typeFilter = types.stream().map(t -> t.typeFilter).reduce(t -> false, Predicate::or);
        }

        public boolean isActive()
        {
            return active;
        }

        public String getTitlePrefix()
        {
            return titlePrefix;
        }

        public boolean matches(AccessTransformer at)
        {
            return typeFilter.test(at.type());
        }
    }

    public static final class ChartTypeValueConverter implements ValueConverter<ChartType.Compound>
    {
        public static final ChartTypeValueConverter INSTANCE = new ChartTypeValueConverter();

        private ChartTypeValueConverter() { }

        @Override
        public ChartType.Compound convert(String value)
        {
            Set<ChartType> types = Arrays.stream(value.split(","))
                    .map(s -> s.toUpperCase(Locale.ROOT))
                    .map(ChartType::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(ChartType.class)));
            if ((types.contains(NONE) || types.contains(ALL)) && types.size() > 1)
            {
                throw new IllegalArgumentException("Chart types 'none' and 'all' cannot be combined with other types!");
            }
            return new Compound(types);
        }

        @Override
        public Class<? extends ChartType.Compound> valueType()
        {
            return ChartType.Compound.class;
        }

        @Override
        public String valuePattern()
        {
            return null;
        }
    }
}
