import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LoadingScreen(
    text: String = "Loading",
    poweredByText: String = "Powered by VERITYPRO"
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = Color.Gray,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(18.dp).toSp() },
                modifier = Modifier.padding(bottom = ScaleUtil.scaleHeight(40.dp))
            )
            DottedCircleLoader()
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ScaleUtil.scaleHeight(30.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = poweredByText,
                color = Color.Gray,
                fontSize = LocalDensity.current.run { ScaleUtil.scaleTextSize(16.dp).toSp() }
            )
            Spacer(modifier = Modifier.height(ScaleUtil.scaleHeight(16.dp)))
        }
    }
}

@Composable
fun DottedCircleLoader(
    dotCount: Int = 12,
    dotColor: Color = Color(0xFF034EBE),
    size: Int = 100,
    durationMillis: Int = 1000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = Modifier.size(ScaleUtil.scaleWidth(size.dp))) {
        val radius = this.size.minDimension / 2
        val dotRadius = radius * 0.1f
        val circleRadius = radius - dotRadius - 8f

        for (i in 0 until dotCount) {
            val angle = (i * (360f / dotCount)) + rotation
            val alpha = (1f - (i.toFloat() / dotCount)).coerceIn(0.2f, 1f)
            val rad = Math.toRadians(angle.toDouble())
            val x = center.x + (circleRadius * cos(rad)).toFloat()
            val y = center.y + (circleRadius * sin(rad)).toFloat()
            drawCircle(
                color = dotColor.copy(alpha = alpha),
                radius = dotRadius,
                center = Offset(x, y)
            )
        }
    }
}