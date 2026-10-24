package cn.xm1221.MieLib.hexcli;

import java.util.List;

/**
 * HexCLI 的假玩家（加载器相关，实现在各平台模块）。
 * <p>
 * 假玩家和普通玩家一样不连网络，但会把发给它的系统消息（hexParse 的“未知符号”等提示、法术的 chat 输出）
 * 收集起来，供 HexCLI 打印到控制台。
 */
public interface HexCliFakePlayer {
    /** 取出并清空已收集的消息。 */
    List<String> drainMessages();
}
