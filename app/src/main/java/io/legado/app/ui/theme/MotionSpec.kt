package io.legado.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import android.provider.Settings

/**
 * 动效 token。数值依据 `project/DESIGN-REFERENCE.md`（Emil Kowalski 那套标准）。
 *
 * 现状是「每个 Activity 各写各的动画、178 处 tween 里只有 15 处用 spring」，所以同样的操作
 * 在不同页面手感不一致。规矩收敛到这一处，调用点只管挑语义，不再自己编时长和曲线。
 *
 * 铁律（违反就是 bug）：
 * - 进入/退出 ≤300ms，**用 ease-out**；永不用 ease-in（起步就慢，手感发黏）
 * - 跟手/位移类一律 spring（物理感），不要线性补间
 * - 永不 scale(0)：从 0 放大看着像「弹出来」，从 0.9 起才像「长出来」
 * - 列表/多项错峰 30~80ms
 * - 尊重系统「减弱动效」：开着时全部退化成瞬时或极短淡入
 */
object MotionSpec {

    /** 按下/松手这类微交互 */
    const val PressMs = 160

    /** 页面、抽屉入场 */
    const val EnterMs = 240

    /** 大面板、抽屉 */
    const val DrawerMs = 280

    /** 多项错峰步长（30~80ms 区间内取中） */
    const val StaggerMs = 40

    /** 进入用 ease-out：开头快、结尾稳。DESIGN.md 记的 cubic-bezier(.2,.9,.25,1) */
    val EaseOut: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

    /** 屏内移动（位置变化），进出都不突兀 */
    val EaseInOut: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** 跟手位移/缩放：低阻尼比，收尾有一点点回弹但不荡 */
    fun <T> springy(
        dampingRatio: Float = 0.86f,
        stiffness: Float = Spring.StiffnessMediumLow,
    ): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

    /** 手指跟手：跟着手指走时不要动画（瞬时），松手回弹才用 spring */
    fun <T> snapTo(): FiniteAnimationSpec<T> = TweenSpec(durationMillis = 0, easing = EaseOut)

    /** 带位移的入场：滑动 + 淡入，用 ease-out */
    fun <T> enter(): FiniteAnimationSpec<T> = tween(durationMillis = EnterMs, easing = EaseOut)

    /** 退场：比入场快一档，避免拖尾 */
    fun <T> exit(): FiniteAnimationSpec<T> = tween(durationMillis = PressMs, easing = EaseOut)

    /** 缩放类入场起始值：绝不从 0 开始 */
    const val ScaleFrom = 0.92f

    /** 位移默认弹簧：专门给「位置变化」用，避免 tween 的机械感 */
    val SpringOffset: SpringSpec<IntOffset> =
        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset.VisibilityThreshold)
}

/**
 * 系统是否开着「减弱动效」。开了就该退化成极短淡入或瞬时，不做位移与缩放。
 * 取系统设置而不是 app 内的开关：这是无障碍设置，用户改的是全局期望。
 */
@Composable
fun rememberMotionReduced(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
