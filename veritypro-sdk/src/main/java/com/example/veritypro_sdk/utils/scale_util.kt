import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ScaleUtil {
    private const val DesignWidth = 375
    private const val DesignHeight = 812

    @Composable
    fun scaleWidth(width: Dp): Dp {
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        return remember(width, screenWidth) { (width.value * (screenWidth.value / DesignWidth.toFloat())).dp }
    }

    @Composable
    fun scaleHeight(height: Dp): Dp {
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp
        return remember(height, screenHeight) { (height.value * (screenHeight.value / DesignHeight.toFloat())).dp }
    }

    @Composable
    fun scaleTextSize(textSize: Dp): Dp {
        val scaledSize = scaleWidth(textSize)
        return remember(textSize, scaledSize) { scaledSize }
    }
}