package cn.xm1221.MieLib.registry

import at.petrak.hexcasting.api.casting.eval.ExecutionClientView
import at.petrak.hexcasting.client.gui.GuiSpellcasting
import cn.xm1221.MieLib.Mielib
import cn.xm1221.MieLib.worker.PriorityWorker
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.ResourceLocation

/**
 * 客户端施法回包（`MsgNewSpellPatternS2C` 与 `GuiSpellcasting.recvServerUpdate`）的集中事件注册表。
 *
 * 避免多个附属同时 mixin `MsgNewSpellPatternS2C.handle` 或 `GuiSpellcasting.recvServerUpdate`。
 * 所有注册均使用 [ResourceLocation] 作为键，同 id 后注册会覆盖旧监听并记录警告日志；
 * 按 `priority` 升序执行（同优先级按 id 字符串稳定排序），单个监听抛异常不会影响其它监听。
 */
object SpellcastingClientEvents {

    fun interface ServerUpdateListener {
        /**
         * 收到服务端 `MsgNewSpellPatternS2C` 并在客户端主线程执行时的回调。
         *
         * @param info 服务端返回的执行视图。
         * @param index 图案下标。
         * @param screen 当前 `Minecraft.getInstance().screen`（`PRE` 阶段在原版可能关闭屏幕之前触发，
         *               `POST` 阶段在原版处理之后触发，包装屏场景下此处仍是包装屏实例）。
         */
        fun onServerUpdate(info: ExecutionClientView, index: Int, screen: Screen?)
    }

    fun interface GuiServerUpdateListener {
        /**
         * `GuiSpellcasting.recvServerUpdate` 完成且未因 `isStackClear` 提前退出后触发。
         * 适用于原版法杖施法界面以及由包装屏委托调用 `recvServerUpdate` 的嵌入式施法网格。
         */
        fun onGuiServerUpdate(gui: GuiSpellcasting, info: ExecutionClientView, index: Int)
    }

    private class ServerUpdateWorker(
        override val priority: Int,
        val listener: ServerUpdateListener,
    ) : PriorityWorker

    private class GuiServerUpdateWorker(
        override val priority: Int,
        val listener: GuiServerUpdateListener,
    ) : PriorityWorker

    private object PreRegistry : HookWorkerRegistry<ServerUpdateWorker>("SpellcastingClientEvents.Pre") {
        fun add(id: ResourceLocation, priority: Int, listener: ServerUpdateListener) =
            put(id, ServerUpdateWorker(priority, listener))
    }

    private object PostRegistry : HookWorkerRegistry<ServerUpdateWorker>("SpellcastingClientEvents.Post") {
        fun add(id: ResourceLocation, priority: Int, listener: ServerUpdateListener) =
            put(id, ServerUpdateWorker(priority, listener))
    }

    private object GuiUpdateRegistry : HookWorkerRegistry<GuiServerUpdateWorker>("SpellcastingClientEvents.Gui") {
        fun add(id: ResourceLocation, priority: Int, listener: GuiServerUpdateListener) =
            put(id, GuiServerUpdateWorker(priority, listener))
    }

    /** 注册 `MsgNewSpellPatternS2C` 处理前的监听（客户端主线程，原版关屏之前）。 */
    @JvmStatic
    fun registerServerUpdatePre(id: ResourceLocation, listener: ServerUpdateListener) =
        PreRegistry.add(id, 0, listener)

    /** 注册 `MsgNewSpellPatternS2C` 处理前的监听，并指定排序优先级（越小越先）。 */
    @JvmStatic
    fun registerServerUpdatePre(id: ResourceLocation, priority: Int, listener: ServerUpdateListener) =
        PreRegistry.add(id, priority, listener)

    /** 注册 `MsgNewSpellPatternS2C` 处理后的监听（客户端主线程，原版处理之后）。 */
    @JvmStatic
    fun registerServerUpdatePost(id: ResourceLocation, listener: ServerUpdateListener) =
        PostRegistry.add(id, 0, listener)

    /** 注册 `MsgNewSpellPatternS2C` 处理后的监听，并指定排序优先级（越小越先）。 */
    @JvmStatic
    fun registerServerUpdatePost(id: ResourceLocation, priority: Int, listener: ServerUpdateListener) =
        PostRegistry.add(id, priority, listener)

    /** 注册 `GuiSpellcasting.recvServerUpdate` 完成后的监听。 */
    @JvmStatic
    fun registerGuiServerUpdate(id: ResourceLocation, listener: GuiServerUpdateListener) =
        GuiUpdateRegistry.add(id, 0, listener)

    /** 注册 `GuiSpellcasting.recvServerUpdate` 完成后的监听，并指定排序优先级（越小越先）。 */
    @JvmStatic
    fun registerGuiServerUpdate(id: ResourceLocation, priority: Int, listener: GuiServerUpdateListener) =
        GuiUpdateRegistry.add(id, priority, listener)

    @JvmStatic
    fun hasServerUpdatePre(): Boolean = !PreRegistry.isEmpty()

    @JvmStatic
    fun hasServerUpdatePost(): Boolean = !PostRegistry.isEmpty()

    @JvmStatic
    fun fireServerUpdatePre(info: ExecutionClientView, index: Int, screen: Screen?) {
        for (worker in PreRegistry.ordered()) {
            try {
                worker.listener.onServerUpdate(info, index, screen)
            } catch (e: Exception) {
                Mielib.LOGGER.warn("SpellcastingClientEvents.Pre: listener {} failed", worker.listener::class.java.name, e)
            }
        }
    }

    @JvmStatic
    fun fireServerUpdatePost(info: ExecutionClientView, index: Int, screen: Screen?) {
        for (worker in PostRegistry.ordered()) {
            try {
                worker.listener.onServerUpdate(info, index, screen)
            } catch (e: Exception) {
                Mielib.LOGGER.warn("SpellcastingClientEvents.Post: listener {} failed", worker.listener::class.java.name, e)
            }
        }
    }

    @JvmStatic
    fun fireGuiServerUpdate(gui: GuiSpellcasting, info: ExecutionClientView, index: Int) {
        for (worker in GuiUpdateRegistry.ordered()) {
            try {
                worker.listener.onGuiServerUpdate(gui, info, index)
            } catch (e: Exception) {
                Mielib.LOGGER.warn("SpellcastingClientEvents.Gui: listener {} failed", worker.listener::class.java.name, e)
            }
        }
    }
}
