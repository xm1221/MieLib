package cn.xm1221.MieLib.genhexbook;

import java.util.List;

/** Bilingual Cloudflare Pages deploy hint for {@code /genHexBook deploy-hint}. */
public final class DeployHint {
    private DeployHint() {}

    public static List<String> lines() {
        return List.of(
            "=== GenHexBook → Cloudflare Pages ===",
            "[中文] 把输出目录部署到 Cloudflare Pages（无需自定义域名）：",
            "  npx wrangler pages deploy <hexbook目录> --project-name mielib-hexbook",
            "[English] Deploy the output directory to Cloudflare Pages (no custom domain needed):",
            "  npx wrangler pages deploy <hexbook-dir> --project-name mielib-hexbook",
            "Requires CLOUDFLARE_API_TOKEN (Pages edit) and account access."
        );
    }
}
