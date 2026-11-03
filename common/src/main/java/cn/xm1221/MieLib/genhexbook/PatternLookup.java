package cn.xm1221.MieLib.genhexbook;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.api.utils.HexUtils;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live action-registry lookup (NOT hexdoc source regex). */
public final class PatternLookup {
    private PatternLookup() {}

    public static Map<String, PatternInfo> all() {
        Map<String, PatternInfo> out = new LinkedHashMap<>();
        Registry<ActionRegistryEntry> regi = IXplatAbstractions.INSTANCE.getActionRegistry();
        for (var entry : regi.entrySet()) {
            ResourceKey<ActionRegistryEntry> key = entry.getKey();
            ActionRegistryEntry are = entry.getValue();
            if (are == null || are.prototype() == null) continue;
            HexPattern proto = are.prototype();
            boolean perWorld = HexUtils.isOfTag(regi, key, HexTags.Actions.PER_WORLD_PATTERN);
            String id = key.location().toString();
            out.put(id, new PatternInfo(
                id,
                proto.getStartDir().toString(),
                proto.anglesSignature(),
                perWorld,
                "hexcasting.action." + key.location()
            ));
        }
        return out;
    }

    public static PatternInfo lookup(String opId) {
        if (opId == null || opId.isBlank()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(opId.contains(":") ? opId : "hexcasting:" + opId);
        if (rl == null) return null;
        Registry<ActionRegistryEntry> regi = IXplatAbstractions.INSTANCE.getActionRegistry();
        ResourceKey<ActionRegistryEntry> key = ResourceKey.create(regi.key(), rl);
        ActionRegistryEntry are = regi.get(key);
        if (are == null || are.prototype() == null) return null;
        HexPattern proto = are.prototype();
        boolean perWorld = false;
        try {
            perWorld = HexUtils.isOfTag(regi, key, HexTags.Actions.PER_WORLD_PATTERN);
        } catch (Exception ignored) {}
        return new PatternInfo(rl.toString(), proto.getStartDir().toString(), proto.anglesSignature(),
            perWorld, "hexcasting.action." + rl);
    }

    public static List<PatternInfo> list() {
        return new ArrayList<>(all().values());
    }
}
