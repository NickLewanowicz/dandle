package net.babybuddy.next.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import net.babybuddy.next.R
import java.io.File
import java.io.FileOutputStream

/**
 * One-shot generator for the widgets' `android:previewImage` PNGs (UX-review W7): launchers on
 * API ≤ 30 ignore `previewLayout` and fall back to the static image, so both provider XMLs ship
 * a rendered preview. Renders the RemoteViews preview layouts via Robolectric's native graphics
 * at xhdpi (2× scale, matching a 2-cell QuickActions ≈ 164dp and the 4×2 dashboard ≈ 332×164dp).
 *
 * Run on demand — it writes into src/main/res/drawable-nodpi/ and is otherwise skipped:
 *   BB_RENDER_PREVIEWS=1 ./gradlew :app:testDebugUnitTest \
 *     --tests 'net.babybuddy.next.widget.WidgetPreviewImageRenderTest'
 * Re-view the output PNGs and commit them together with any layout change.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewImageRenderTest {

    @Test
    fun `render preview images for pre-S launchers`() {
        assumeTrue(
            "Generator only runs when BB_RENDER_PREVIEWS=1",
            System.getenv("BB_RENDER_PREVIEWS") == "1",
        )
        renderTo(R.layout.preview_quick_actions, 328, 328, "preview_quick_actions.png")
        // 4×2 default placement ≈ 332×187dp (review-measured cell grid on Pixel 5).
        renderTo(R.layout.preview_care_dashboard, 664, 374, "preview_care_dashboard.png")
    }

    private fun renderTo(@LayoutRes layoutId: Int, widthPx: Int, heightPx: Int, fileName: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = LayoutInflater.from(context).inflate(layoutId, null)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val outDir = File(System.getProperty("user.dir"), "src/main/res/drawable-nodpi")
        outDir.mkdirs()
        val out = File(outDir, fileName)
        FileOutputStream(out).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        println("WROTE ${out.absolutePath}")
    }
}
