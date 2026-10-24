package cn.xm1221.MieLib.hexcli.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tab 补全：行首补全命令名和图案名，{@code env use} / {@code profile use} 补全 id / 名字；
 * 其它情况按 hexParse 图案名（服务器 {@code complete} 请求给的 action registry 名字）补全。
 * 这些列表由 {@link HexCliClient} 在连接后填入。
 */
public final class Completer implements org.jline.reader.Completer {
    private static final List<String> ENV_SUB = List.of("list", "use");
    private static final List<String> PROFILE_SUB = List.of("list", "show", "use");

    private final Set<String> patterns = new TreeSet<>();
    /** {@code --file} 后补全路径：只列目录和 .hexparse / .hex / .txt 文件。 */
    private final org.jline.builtins.Completers.FileNameCompleter files = new org.jline.builtins.Completers.FileNameCompleter() {
        @Override
        protected boolean accept(java.nio.file.Path path) {
            if (!super.accept(path)) return false;
            if (java.nio.file.Files.isDirectory(path)) return true;
            String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return n.endsWith(".hexparse") || n.endsWith(".hex") || n.endsWith(".txt");
        }
    };
    private final Set<String> envs = new TreeSet<>(List.of("auto"));
    private final Set<String> profiles = new TreeSet<>();

    public synchronized void update(JsonObject data) {
        if (data == null) return;
        JsonArray p = Connection.array(data, "patterns");
        patterns.clear();
        for (JsonElement e : p) patterns.add(e.getAsString());
        JsonArray e = Connection.array(data, "envs");
        if (e.size() > 0) {
            envs.clear();
            for (JsonElement x : e) envs.add(x.getAsString());
        }
        JsonArray pr = Connection.array(data, "profiles");
        profiles.clear();
        for (JsonElement x : pr) profiles.add(x.getAsString());
    }

    public synchronized void clear() {
        patterns.clear();
        envs.clear();
        envs.add("auto");
        profiles.clear();
    }

    @Override
    public synchronized void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        List<String> words = line.words();
        int idx = line.wordIndex();
        String first = words.isEmpty() ? "" : words.get(0);

        if (idx == 0) {
            // 行首：命令名 + 图案名
            for (String m : HexCliClient.META_NAMES) if (m.startsWith(word)) candidates.add(new Candidate(m));
            add(candidates, patterns, word);
            return;
        }
        if (HexCliClient.META_NAMES.contains(first)) {
            if (first.equals("env")) {
                if (idx == 1) add(candidates, new TreeSet<>(ENV_SUB), word);
                else if (idx == 2 && words.get(1).equals("use")) add(candidates, envs, word);
                return;
            }
            if (first.equals("profile")) {
                if (idx == 1) add(candidates, new TreeSet<>(PROFILE_SUB), word);
                else if (idx == 2 && words.get(1).equals("use")) add(candidates, profiles, word);
                return;
            }
            if (first.equals("run") || first.equals("cast")) {
                if (idx >= 2 && words.get(idx - 1).equals("--file")) {
                    files.complete(reader, line, candidates);
                    return;
                }
                if (word.startsWith("-")) {
                    for (String f : RunArgs.FLAGS) if (f.startsWith(word)) candidates.add(new Candidate(f));
                    return;
                }
            } else {
                return;
            }
        }
        add(candidates, patterns, word);
    }

    private static void add(List<Candidate> candidates, Set<String> values, String prefix) {
        for (String v : values) {
            if (prefix.isEmpty() || v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                candidates.add(new Candidate(v));
            }
        }
    }
}
