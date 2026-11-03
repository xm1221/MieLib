package cn.xm1221.MieLib.genhexbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Static SVG glyph from Hex start direction + angle signature ({@code qweasd}).
 * Geometry matches hexdoc's {@code hexcasting.js} drawer.
 */
public final class PatternSvg {
    private PatternSvg() {}

    public static String svg(String startDir, String signature, boolean perWorld, int size) {
        List<double[]> pts = points(startDir, signature == null ? "" : signature);
        if (pts.isEmpty()) pts.add(new double[]{0, 0});
        double minX = pts.get(0)[0], minY = pts.get(0)[1], maxX = minX, maxY = minY;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]);
        }
        double span = Math.max(3, Math.max(maxX - minX, maxY - minY));
        double scale = (size * 0.8) / span;
        double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5;
        StringBuilder path = new StringBuilder();
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            double x = size * 0.5 + scale * (pts.get(i)[0] - cx);
            double y = size * 0.5 + scale * (pts.get(i)[1] - cy);
            if (i == 0) path.append(String.format(Locale.ROOT, "M %.2f %.2f", x, y));
            else path.append(String.format(Locale.ROOT, " L %.2f %.2f", x, y));
            double r = (i == 0 && !perWorld) ? 4.5 : 3.0;
            String fill = (i == 0 && !perWorld) ? "#e8c36a" : "#c9d1d9";
            dots.append(String.format(Locale.ROOT,
                "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\"/>", x, y, r, fill));
        }
        String stroke = perWorld ? "#f0883e" : "#58a6ff";
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + size + "\" height=\"" + size
            + "\" viewBox=\"0 0 " + size + " " + size + "\" class=\"pattern-svg"
            + (perWorld ? " per-world" : "") + "\">"
            + "<rect width=\"100%\" height=\"100%\" fill=\"#0d1117\" rx=\"8\"/>"
            + "<path d=\"" + path + "\" fill=\"none\" stroke=\"" + stroke
            + "\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
            + dots + "</svg>";
    }

    static List<double[]> points(String startDir, String signature) {
        int angle = startAngle(startDir);
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{0, 0});
        double[] last = points.get(0);
        // leading 'w' matches hexdoc JS (apply start without turning)
        String seq = "w" + signature.toLowerCase(Locale.ROOT);
        for (int i = 0; i < seq.length(); i++) {
            int add = offsetAngle(seq.charAt(i));
            if (add < 0) continue;
            angle = (angle + add) % 6;
            double trueAngle = (Math.PI / 3.0) * angle;
            double[] np = new double[]{
                last[0] + Math.cos(trueAngle),
                last[1] - Math.sin(trueAngle)
            };
            points.add(np);
            last = np;
        }
        return points;
    }

    static int startAngle(String startDir) {
        if (startDir == null) return 0;
        return switch (startDir.toUpperCase(Locale.ROOT)) {
            case "EAST" -> 0;
            case "NORTH_EAST", "NORTHEAST" -> 1;
            case "NORTH_WEST", "NORTHWEST" -> 2;
            case "WEST" -> 3;
            case "SOUTH_WEST", "SOUTHWEST" -> 4;
            case "SOUTH_EAST", "SOUTHEAST" -> 5;
            default -> 0;
        };
    }

    static int offsetAngle(char ch) {
        return switch (ch) {
            case 'w' -> 0;
            case 'q' -> 1;
            case 'a' -> 2;
            case 's' -> 3;
            case 'd' -> 4;
            case 'e' -> 5;
            default -> -1;
        };
    }
}
