package cn.xm1221.MieLib.hexcli.remote;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 一次 HexCLI 操作的结果：给人看的文本行 + 结构化数据（远程客户端用，可以为空）+ 成败标志。
 * 命令处理器只打印 {@link #lines()}；远程服务器把两者一起发回去。
 */
public record HexCliReply(List<String> lines, JsonObject data, boolean ok) {
    public HexCliReply {
        lines = List.copyOf(lines);
        if (data == null) data = new JsonObject();
    }

    public static HexCliReply ok(List<String> lines) {
        return new HexCliReply(lines, new JsonObject(), true);
    }

    public static HexCliReply ok(List<String> lines, JsonObject data) {
        return new HexCliReply(lines, data, true);
    }

    public static HexCliReply fail(List<String> lines) {
        return new HexCliReply(lines, new JsonObject(), false);
    }

    public static HexCliReply fail(List<String> lines, JsonObject data) {
        return new HexCliReply(lines, data, false);
    }

    /** 一行成功。 */
    public static HexCliReply line(String line) {
        return ok(List.of(line));
    }
}
