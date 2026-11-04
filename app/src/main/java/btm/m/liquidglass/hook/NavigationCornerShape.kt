package btm.m.liquidglass.hook

import androidx.compose.ui.unit.dp
import btm.m.liquidglass.NavigationCornerSettings
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import com.kyant.shapes.lerp

/** Retains the renderer's rounded-rectangle/SDF contract at every corner adjustment. */
internal fun navigationCornerShape(settings: NavigationCornerSettings.Settings): RoundedRectangularShape =
    if (!settings.enabled || settings.value == 0) Capsule()
    else lerp(RoundedRectangle(0.dp), Capsule(), settings.radiusFactor)
