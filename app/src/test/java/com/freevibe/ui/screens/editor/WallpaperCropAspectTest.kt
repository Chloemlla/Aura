package com.freevibe.ui.screens.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Wallpaper
import com.freevibe.data.model.WallpaperTarget
import com.freevibe.service.WallpaperApplier
import com.freevibe.service.WallpaperApplyCoordinator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Loads a real picture into the crop view model and exports it, with only the final apply faked. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperCropAspectTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val applier = mockk<WallpaperApplier>()
    private val coordinator = mockk<WallpaperApplyCoordinator>()
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `each preset is the only one selected and reframes the crop`() = runBlocking {
        val vm = loaded(viewModel(), picture("tall"))
        val bitmap = vm.state.value.bitmap!!
        val free = cropSourceRect(bitmap.width, bitmap.height, VIEWPORT_W, VIEWPORT_H, CropAspect.FREE, vm.transform())

        for (aspect in CropAspect.entries.filter { it.ratio != null }) {
            vm.selectAspect(aspect)

            assertEquals(aspect, vm.state.value.aspect)
            val rect = cropSourceRect(bitmap.width, bitmap.height, VIEWPORT_W, VIEWPORT_H, aspect, vm.transform())
            assertNotEquals(aspect.name, free, rect)
            val frame = cropFrame(VIEWPORT_W, VIEWPORT_H, aspect)
            val total = cropFitScale(bitmap.width, bitmap.height, VIEWPORT_W, VIEWPORT_H) * vm.state.value.scale
            assertEquals("${aspect.name} fills its frame", frame.width / total, rect.width.toFloat(), 1f)
            assertEquals("${aspect.name} fills its frame", frame.height / total, rect.height.toFloat(), 1f)
        }
    }

    @Test
    fun `the crop survives the view model being recreated`() = runBlocking {
        val tall = picture("tall")
        val first = loaded(viewModel(), tall)
        first.selectAspect(CropAspect.SQUARE)
        first.applyGesture(zoom = 1.25f, panX = 40f, panY = -30f)
        val saved = first.state.value

        val restored = loaded(viewModel(), tall).state.value

        assertEquals(CropAspect.SQUARE, restored.aspect)
        assertEquals(saved.scale, restored.scale, 0.0001f)
        assertEquals(saved.offsetX, restored.offsetX, 0.01f)
        assertEquals(saved.offsetY, restored.offsetY, 0.01f)
    }

    @Test
    fun `another wallpaper starts from Free`() = runBlocking {
        loaded(viewModel(), picture("tall")).selectAspect(CropAspect.SQUARE)

        val other = loaded(viewModel(), picture("other")).state.value

        assertEquals(CropAspect.FREE, other.aspect)
        assertEquals(1f, other.scale, 0f)
    }

    @Test
    fun `Reset goes back to Free`() = runBlocking {
        val vm = loaded(viewModel(), picture("tall"))
        vm.selectAspect(CropAspect.LANDSCAPE)

        vm.resetTransform()

        assertEquals(CropAspect.FREE, vm.state.value.aspect)
        assertEquals(CropTransform(), vm.transform())
    }

    @Test
    fun `applying exports the selected ratio within a pixel`() = runBlocking {
        val vm = loaded(viewModel(), picture("tall"))
        for (aspect in CropAspect.entries.filter { it.ratio != null }) {
            val exported = CompletableDeferred<Pair<Int, Int>>()
            coEvery { applier.applyFromBitmap(any(), any(), any()) } answers {
                val bitmap = firstArg<Bitmap>()
                exported.complete(bitmap.width to bitmap.height)
                Result.success(Unit)
            }
            coEvery { coordinator.apply(any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<suspend () -> Result<Unit>>(5).invoke()
                Result.failure(IllegalStateException("no receipt needed"))
            }
            vm.selectAspect(aspect)

            vm.applyCropped(WallpaperTarget.HOME, VIEWPORT_W, VIEWPORT_H)
            val (width, height) = withTimeout(10_000) { exported.await() }

            val ratio = aspect.ratio!!
            assertTrue("$aspect exported ${width}x$height", abs(width - height * ratio) <= 1f || abs(height - width / ratio) <= 1f)
        }
    }

    private fun viewModel() = WallpaperCropViewModel(
        wallpaperApplier = applier,
        okHttpClient = mockk(relaxed = true),
        smartCropDetector = mockk(relaxed = true),
        appContext = context,
        applyCoordinator = coordinator,
        savedStateHandle = savedState,
    )

    private suspend fun loaded(vm: WallpaperCropViewModel, wallpaper: Wallpaper): WallpaperCropViewModel {
        vm.loadWallpaper(wallpaper)
        withTimeout(10_000) { vm.state.first { it.bitmap != null } }
        vm.setViewport(VIEWPORT_W, VIEWPORT_H)
        return vm
    }

    private fun WallpaperCropViewModel.transform() = state.value.let { CropTransform(it.scale, it.offsetX, it.offsetY) }

    private fun picture(id: String): Wallpaper {
        val file = File(context.cacheDir, "crop_$id.png")
        val bitmap = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val locator = Uri.fromFile(file).toString()
        return Wallpaper(
            id = id,
            source = ContentSource.LOCAL,
            thumbnailUrl = locator,
            fullUrl = locator,
            width = 540,
            height = 1200,
        )
    }

    private companion object {
        const val VIEWPORT_W = 1080
        const val VIEWPORT_H = 1700
    }
}
