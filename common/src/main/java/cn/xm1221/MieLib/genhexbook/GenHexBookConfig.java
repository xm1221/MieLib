package cn.xm1221.MieLib.genhexbook;

/**
 * Runtime toggles for /genHexBook.
 * {@code -Dmielib.genhexbook.revealGreat=true} shows per-world note without changing
 * that we always emit the <em>canonical registry prototype</em> (never scrungled world angles).
 */
public final class GenHexBookConfig {
    public static final String REVEAL_PROP = "mielib.genhexbook.revealGreat";

    private GenHexBookConfig() {}

    public static boolean revealGreatSpells() {
        return "true".equalsIgnoreCase(System.getProperty(REVEAL_PROP, "false"));
    }
}
