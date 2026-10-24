package cn.xm1221.MieLib.hexcli.api;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一个已注册环境 id 的完整描述（不可变）。要么用 {@link #of} 速写，要么用 {@link #builder} 完整构造。
 * <pre>{@code
 * HexCliAPI.registerEnv(new ResourceLocation("my_mod", "mob"),
 *     EnvSpec.builder(factory)
 *         .casters(CasterKind.LIVING)
 *         .description("my non-player caster env")
 *         .source("my_mod")          // 可选，默认取 id 的命名空间
 *         .build());
 * }</pre>
 */
public final class EnvSpec {
    private final HexCliEnvFactory factory;
    private final Set<CasterKind> casters;
    private final String description;
    private final @Nullable String source;

    private EnvSpec(HexCliEnvFactory factory, Set<CasterKind> casters, String description, @Nullable String source) {
        if (factory == null) throw new IllegalArgumentException("EnvSpec factory must not be null");
        this.factory = factory;
        this.casters = casters;
        this.description = description;
        this.source = source;
    }

    /** 构造工厂。 */
    public static Builder builder(HexCliEnvFactory factory) {
        return new Builder(factory);
    }

    /** 速写：描述 + 施法者种类（至少一个）。 */
    public static EnvSpec of(HexCliEnvFactory factory, String description, CasterKind... kinds) {
        return builder(factory).description(description).casters(kinds).build();
    }

    public HexCliEnvFactory factory() {
        return factory;
    }

    /** 该环境支持的施法者种类（非空）。 */
    public Set<CasterKind> casters() {
        return casters;
    }

    public String description() {
        return description;
    }

    /** 注册该环境的 mod id（可选，默认取 id 的命名空间）。 */
    public @Nullable String source() {
        return source;
    }

    /** 当前施法者是否符合该环境要求。 */
    public boolean accepts(@Nullable LivingEntity caster) {
        return casters.contains(CasterKind.of(caster));
    }

    /** 例如 {@code "player|living"}，用于报错与 {@code hexcli env list}。 */
    public String castersText() {
        StringBuilder sb = new StringBuilder();
        for (CasterKind k : CasterKind.values()) {
            if (casters.contains(k)) {
                if (sb.length() > 0) sb.append('|');
                sb.append(switch (k) {
                    case PLAYER -> "player";
                    case LIVING -> "living";
                    case NONE -> "none";
                });
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "EnvSpec{casters=" + castersText() + ", description='" + description + "'}";
    }

    /** 构造器：必须先 {@link #casters(CasterKind...)}（非空），否则 {@link #build()} 报错。 */
    public static final class Builder {
        private final HexCliEnvFactory factory;
        private Set<CasterKind> casters = EnumSet.noneOf(CasterKind.class);
        private String description = "";
        private @Nullable String source;

        private Builder(HexCliEnvFactory factory) {
            this.factory = factory;
        }

        /** 该环境支持的施法者种类（至少一个；可多次调用叠加）。 */
        public Builder casters(CasterKind... kinds) {
            for (CasterKind k : kinds) casters.add(k);
            return this;
        }

        /** 接受全部三种施法者（player|living|none）。 */
        public Builder anyCaster() {
            casters = EnumSet.allOf(CasterKind.class);
            return this;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        /** 注册该环境的 mod id；省略则默认取 id 的命名空间。 */
        public Builder source(@Nullable String source) {
            this.source = source;
            return this;
        }

        public EnvSpec build() {
            if (casters.isEmpty())
                throw new IllegalArgumentException("EnvSpec needs at least one caster kind");
            return new EnvSpec(factory, Collections.unmodifiableSet(EnumSet.copyOf(casters)), description, source);
        }
    }
}
