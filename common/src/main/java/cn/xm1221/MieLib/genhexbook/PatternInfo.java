package cn.xm1221.MieLib.genhexbook;

/** One action from the live Hex Casting registry. */
public record PatternInfo(
    String id,
    String startDir,
    String signature,
    boolean perWorld,
    String translationKey
) {}
