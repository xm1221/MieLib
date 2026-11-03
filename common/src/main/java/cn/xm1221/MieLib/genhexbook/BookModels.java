package cn.xm1221.MieLib.genhexbook;

import java.util.ArrayList;
import java.util.List;

public final class BookModels {
    private BookModels() {}

    public record Category(String id, String nameKey, String descKey, String parent, int sortnum, String icon) {}

    public record Entry(String id, String nameKey, String category, String icon, int sortnum,
                        String advancement, List<Page> pages) {}

    public sealed interface Page permits TextPage, PatternPage, SpotlightPage, CraftingPage, ImagePage, OtherPage {}

    public record TextPage(String text) implements Page {}
    public record PatternPage(String opId, String input, String output, String text, String anchor) implements Page {}
    public record SpotlightPage(String item, String text, String title) implements Page {}
    public record CraftingPage(String recipe, String text, String title) implements Page {}
    public record ImagePage(List<String> images, String text, String title) implements Page {}
    public record OtherPage(String type, String rawJson) implements Page {}

    public static List<Page> emptyPages() { return new ArrayList<>(); }
}
