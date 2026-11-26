package cn.xm1221.MieLib.client

import at.petrak.hexcasting.api.casting.eval.ResolvedPattern
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.client.gui.GuiSpellcasting
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec2
import java.util.function.Consumer

/**
 * 供嵌入式施法网格（思索沙盒、法术 IDE、手绘弹窗等）与客户端视觉辅助
 * 读取和控制 [GuiSpellcasting] 会话状态的公共接口（由 MieLib Mixin 实现）。
 */
interface SpellcastingGuiAccess {
    /** 打开该施法界面时使用的手。 */
    val handOpenedWith: InteractionHand

    /** 当前已绘制图案列表的只读视图。 */
    val patterns: List<ResolvedPattern>

    /** 当前缓存的服务端施法栈 NBT 列表（只读副本）。 */
    val cachedStack: List<CompoundTag>

    /** 是否处于「已落笔或正在拖拽绘制」状态（即 `drawState` 不是 `BetweenPatterns`）。 */
    val isStrokeActive: Boolean

    /** 是否已经画出至少一条线段（`PatternDrawState.Drawing`）。 */
    val isDrawing: Boolean

    /** 正在绘制中的临时图案（仅在 [isDrawing] 为 `true` 时非空）。 */
    val wipPattern: HexPattern?

    /** 覆盖六边形格子大小；传 `null` 或 `Float.NaN` 恢复原版计算。 */
    fun setHexSizeOverride(hexSize: Float?)

    /** 覆盖网格中心偏移；传 `null` 恢复原版居中计算。 */
    fun setCoordsOffsetOverride(offset: Vec2?)

    /** 同时设置网格大小与中心偏移覆盖；传 `null` 恢复原版计算。 */
    fun setViewport(hexSize: Float?, offset: Vec2?)

    /** 设置是否允许 `GuiSpellcasting.tick()` 执行原版手持法杖/虚弱检测（沙盒模式设为 `false`）。 */
    fun setTickEnabled(enabled: Boolean)

    /**
     * 设置是否在 `drawEnd` 时向服务端发送 `MsgNewSpellPatternC2S`（默认 `true`）。
     * 设为 `false` 时仅记录本地图案并触发 [setOnDrawPattern] 回调。
     */
    fun setSendPatternPackets(send: Boolean)

    /** 设置绘制完成一笔图案时的本地回调（传 `null` 清除）。 */
    fun setOnDrawPattern(callback: Consumer<HexPattern>?)

    /**
     * 设置收到 `isStackClear == true` 的服务端回包时是否保持界面打开（默认 `false`）。
     * 为 `true` 时，调用 `recvServerUpdate` 仅清空本地栈显示而不关闭当前屏幕。
     */
    fun setKeepOpenOnStackClear(keepOpen: Boolean)

    /** 清空已绘制图案与已占用点位。 */
    fun clearPatterns()

    /** 设置指定索引图案的解析状态类型；索引越界时忽略。 */
    fun setPatternType(index: Int, type: ResolvedPatternType)

    /** 设置最后一笔图案的解析状态类型；无图案时忽略。 */
    fun setLastPatternType(type: ResolvedPatternType)

    /** 清空本地缓存的施法栈显示并刷新显示文本（不关闭界面）。 */
    fun clearStackDisplay()

    /** 重新计算栈与渡鸦之思的显示文本缓存。 */
    fun recalculateIotaDisplays()

    /**
     * 取消当前未完成的一笔绘制（把 `drawState` 重置为 `BetweenPatterns`），不关闭界面。
     * @return 若原本处于绘制中并成功取消则返回 `true`。
     */
    fun cancelStroke(): Boolean

    companion object {
        /** 获取 [gui] 上的 [SpellcastingGuiAccess] 会话控制接口（Java 调用便捷入口）。 */
        @JvmStatic
        fun of(gui: GuiSpellcasting): SpellcastingGuiAccess = gui as SpellcastingGuiAccess
    }
}

/** 获取 [GuiSpellcasting] 上的 [SpellcastingGuiAccess] 会话控制接口。 */
fun GuiSpellcasting.mielib(): SpellcastingGuiAccess = this as SpellcastingGuiAccess
