package cn.xm1221.MieLib.hexcli.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * 命令后面的 {@code --xxx} 选项（通用约定：选项写在命令名之后、正文之前）。
 * 目前 {@code run} / {@code cast} 支持 {@code --step} 和 {@code --file <path>}。
 */
final class RunArgs {
    boolean step;
    String file;
    String code = "";
    String error;

    /** 解析 {@code rest}（命令名之后的部分）。只认开头连续的 {@code --xxx}；{@code --} 之后全是正文。 */
    static RunArgs parse(String rest) {
        RunArgs a = new RunArgs();
        String s = rest.strip();
        while (s.startsWith("--")) {
            String[] t = s.split("\\s+", 2);
            String flag = t[0];
            s = t.length > 1 ? t[1].strip() : "";
            switch (flag) {
                case "--" -> {
                    a.code = s;
                    return a.finish();
                }
                case "--step" -> a.step = true;
                case "--file" -> {
                    if (s.isEmpty()) {
                        a.error = "--file needs a path";
                        return a;
                    }
                    String path;
                    if (s.startsWith("\"")) {
                        int end = s.indexOf('"', 1);
                        if (end < 0) {
                            a.error = "unterminated quote in --file path";
                            return a;
                        }
                        path = s.substring(1, end);
                        s = s.substring(end + 1).strip();
                    } else {
                        String[] u = s.split("\\s+", 2);
                        path = u[0];
                        s = u.length > 1 ? u[1].strip() : "";
                    }
                    a.file = path;
                }
                default -> {
                    a.error = "unknown option " + flag + " (run/cast take --step, --file <path>)";
                    return a;
                }
            }
        }
        a.code = s;
        return a.finish();
    }

    private RunArgs finish() {
        if (file != null) {
            if (!code.isEmpty()) {
                error = "give either --file <path> or code, not both";
                return this;
            }
            Path p = resolve(file);
            try {
                code = Files.readString(p, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                error = "file not found: " + p.toAbsolutePath();
                return this;
            } catch (IOException e) {
                error = "cannot read " + p.toAbsolutePath() + ": " + e.getMessage();
                return this;
            }
            if (code.isBlank()) error = "file is empty: " + p.toAbsolutePath();
        } else if (code.isEmpty()) {
            error = "nothing to cast (give code or --file <path>)";
        }
        return this;
    }

    static Path resolve(String file) {
        if (file.equals("~") || file.startsWith("~/") || file.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home") + file.substring(1));
        }
        return Path.of(file);
    }

    static final List<String> FLAGS = List.of("--step", "--file");
}
