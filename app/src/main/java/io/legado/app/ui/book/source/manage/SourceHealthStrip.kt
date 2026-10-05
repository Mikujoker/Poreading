package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.TextAutoSize
import java.util.Locale
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import androidx.compose.ui.res.stringResource

/**
 * 书源健康度的配色。暖色系，随明暗切换；饱和度刻意压低，避免整页被状态色淹没。
 */
private data class HealthColors(
    val active: Color,
    val idle: Color,
    val frozen: Color,
    val needFix: Color,
    val broken: Color,
)

@Composable
private fun healthColors(): HealthColors = if (isSystemInDarkTheme()) {
    HealthColors(
        active = Color(0xFF8FB073),
        idle = Color(0xFF93A5B8),
        frozen = Color(0xFF7E9BC0),
        needFix = Color(0xFFD9A544),
        broken = Color(0xFFD4705C),
    )
} else {
    HealthColors(
        active = Color(0xFF6C8A52),
        idle = Color(0xFF9E917F),
        frozen = Color(0xFF76839A),
        needFix = Color(0xFFB3862A),
        broken = Color(0xFFB04E38),
    )
}

private fun HealthColors.of(health: SourceHealth): Color = when (health) {
    SourceHealth.ACTIVE -> active
    SourceHealth.IDLE -> idle
    SourceHealth.FROZEN -> frozen
    SourceHealth.NEED_FIX -> needFix
    SourceHealth.BROKEN -> broken
}

@Composable
private fun SourceHealth.label(): String = stringResource(
    when (this) {
        SourceHealth.ACTIVE -> R.string.source_health_active
        SourceHealth.IDLE -> R.string.source_health_idle
        SourceHealth.FROZEN -> R.string.source_health_frozen
        SourceHealth.NEED_FIX -> R.string.source_health_need_fix
        SourceHealth.BROKEN -> R.string.source_health_broken
    }
)

/**
 * 压缩计数，最多 4 个字符：12345 -> 1.2w、3493 -> 3.5k、631 -> 631。
 * 手机上一格只有约 40dp 宽，完整数字排不下，压缩后还能把字号留在可读区间。
 */
private fun compactCount(count: Int): String {
    val (value, unit) = when {
        count >= 10_000 -> count / 10_000.0 to "w"
        count >= 1_000 -> count / 1_000.0 to "k"
        else -> return count.toString()
    }
    return String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") + unit
}

/** 供列表行取色：状态色只在色块与数字上出现，不进正文。 */
@Composable
fun sourceHealthTint(health: SourceHealth): Color = healthColors().of(health)

/** 统计条的固定顺序，选中态用同一个枚举表达。 */
private val STRIP_ORDER = listOf(
    SourceHealth.ACTIVE,
    SourceHealth.IDLE,
    SourceHealth.FROZEN,
    SourceHealth.NEED_FIX,
    SourceHealth.BROKEN,
)

/**
 * 五级健康度统计条，同时充当筛选器。再点一次已选中的格子即取消筛选。
 */
@Composable
fun SourceHealthStrip(
    counts: Map<SourceHealth, Int>,
    selected: SourceHealth?,
    onSelect: (SourceHealth?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = healthColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        STRIP_ORDER.forEach { health ->
            val tint = colors.of(health)
            val isOn = selected == health
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(tint.copy(alpha = if (isOn) 0.16f else 0.06f))
                    .border(
                        width = if (isOn) 1.5.dp else 1.dp,
                        color = tint.copy(alpha = if (isOn) 1f else 0.34f),
                        shape = shape,
                    )
                    .clickable { onSelect(if (isOn) null else health) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            ) {
                Text(
                    text = compactCount(counts[health] ?: 0),
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    // 手机窄屏下一格只有 40 多 dp，五位数字排不下，交给自动缩字号
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 13.sp,
                        maxFontSize = 20.sp,
                        stepSize = 0.5.sp,
                    ),
                )
                Text(
                    text = health.label(),
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 9.sp,
                        maxFontSize = 11.sp,
                        stepSize = 0.5.sp,
                    ),
                )
            }
        }
    }
}
