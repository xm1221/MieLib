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
 * Tab 补全：行首 {@code :} 时补全 meta 命令，{@code :env use} / {@code :profile use} 补全 id / 名字；
 * 其它情况按 hexParse 图案名（服务器 {@code complete} 请求给的 action registry 名字）补全。
 * 这些列表由 {@link HexCliClient} 在连接后填入。
 */
public final class Completer implements org.jline.reader.Completer {
    private static final List<String> META = List.of(
        ":run", ":stack", ":reset", ":env", ":env list", ":env use", ":profile", ":profile list",
        ":profile show", ":profile use", ":media", ":tick", ":wait", ":cmd", ":help", ":quit", ":q", ":exit");

    private final Set<String> patterns = new TreeSet<>();
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
        String buffer = line.line();
        String word = line.word();
        String trimmed = buffer.stripLeading();

        if (trimmed.startsWith(":")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && parts[0].equals(":env") && parts[1].equals("use")) {
                add(candidates, envs, word);
                return;
            }
            if (parts.length >= 2 && parts[0].equals(":profile") && parts[1].equals("use")) {
                add(candidates, profiles, word);
                return;
            }
            if (word.startsWith(":") || parts.length <= 1) {
                for (String m : META) {
                    if (m.startsWith(word)) candidates.add(new Candidate(m));
                }
                return;
            }
            return;
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
