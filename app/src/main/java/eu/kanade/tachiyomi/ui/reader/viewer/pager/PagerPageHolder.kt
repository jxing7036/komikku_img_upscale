package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.max

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job for loading the page.
     */
    private var extraLoadJob: Job? = null

    /**
     * Job that keeps the two per-page enhancement status rows up to date in double-page mode.
     */
    private var enhancementStatusJob: Job? = null

    // KMK -->
    private val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }
    // KMK <--

    init {
        // KMK -->
        // Set page index for enhancement priority tracking
        pageIndex = page.index
        mangaId = viewer.activity.viewModel.manga?.id ?: -1L
        chapterId = page.chapter.chapter.id ?: -1L
        readerPage = page
        // KMK <--
        loadJob = scope.launch { loadPageAndProcessStatus(1) }
        // SY -->
        extraLoadJob = scope.launch { loadPageAndProcessStatus(2) }
        // SY <--
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        enhancementStatusJob?.cancel()
        enhancementStatusJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(
                context = context,
                // KMK -->
                seedColor = seedColor,
                // KMK <--
            )
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus(pageIndex: Int) {
        // SY -->
        val page = if (pageIndex == 1) page else extraPage
        page ?: return
        // SY <--
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        if (extraPage == null) {
            progressIndicator?.setProgress(0)
        } else {
            progressIndicator?.setProgress(95)
        }

        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream

        try {
            val (source, isAnimated, background) = withIOContext {
                streamFn().buffered(16).use { source ->
                    // SY -->
                    if (extraPage != null) {
                        streamFn2?.invoke()
                            ?.buffered(16)
                    } else {
                        null
                    }.use { source2 ->
                        val itemSource = if (viewer.config.dualPageSplit) {
                            process(item.first, Buffer().readFrom(source))
                        } else {
                            mergePages(Buffer().readFrom(source), source2?.let { Buffer().readFrom(it) })
                        }
                        // SY <--
                        val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
                        val background = if (!isAnimated && viewer.config.automaticBackground) {
                            ImageUtil.chooseBackground(context, itemSource.peek())
                        } else {
                            null
                        }
                        Triple(itemSource, isAnimated, background)
                    }
                }
            }
            withUIContext {
                setupDoublePageEnhancement()
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                        // KMK -->
                        disableZoomIn = viewer.config.disableZoomIn,
                        doubleTapZoom = viewer.config.doubleTapZoom,
                        landscapeZoomScaleType = viewer.config.landscapeZoomScaleType,
                        // KMK <--
                    ),
                    // KMK -->
                    streamFn,
                    // KMK <--
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun setupDoublePageEnhancement() {
        val second = extraPage
        val isRealDoublePage =
            viewer.config.doublePages &&
                !viewer.config.dualPageSplit &&
                second != null &&
                !page.fullPage &&
                !second.fullPage &&
                readerPreferences.realCuganEnabled().get()
        if (!isRealDoublePage) {
            suppressSinglePageProcessedSwap = false
            enhancedImageSourceFactory = null
            suppressDefaultStatus = false
            hideEnhancementStatus()
            return
        }
        suppressSinglePageProcessedSwap = true
        enhancedImageSourceFactory = { firstFile ->
            buildDoublePageSource(firstFile)
        }
        // The base view only tracks one page status; in double-page mode we show two rows ourselves.
        suppressDefaultStatus = true
        startEnhancementStatusTracking()
    }

    /**
     * Builds the merged double-page source from the enhanced cache of both pages. Returns null
     * until BOTH pages are enhanced, so the merged view is refreshed once, only when both are done.
     */
    private fun buildDoublePageSource(firstFile: File): BufferedSource? {
        val second = extraPage ?: return null
        val mId = page.chapter.chapter.manga_id ?: -1L
        val cId = page.chapter.chapter.id ?: -1L
        if (mId == -1L || cId == -1L) return null
        if (!readerPreferences.realCuganEnabled().get()) return null
        ImageEnhancementCache.init(context)
        val configHash = enhancementConfigHash()

        val secondFile = ImageEnhancementCache.getCachedImage(
            mId,
            cId,
            second.index,
            configHash,
            second.enhancementKeySuffix,
        ) ?: return null

        val bitmap1 = decodeEnhancedBitmap(firstFile) ?: return null
        val bitmap2 = decodeEnhancedBitmap(secondFile) ?: return null
        try {
            val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
            // Scale the center margin by the upscale factor so the physical gap stays constant
            // before and after enhancement.
            val rawMargin = calculateCenterMargin(bitmap1.height, bitmap2.height)
            val effectiveScale = ImageEnhancementCache.getEffectiveScale(
                model = readerPreferences.realCuganModel().get(),
                scale = readerPreferences.realCuganScale().get(),
                realEsrganStyle = readerPreferences.realEsrganStyle().get(),
            ).coerceAtLeast(1)
            val centerMargin = (rawMargin * effectiveScale).coerceAtLeast(0)
            return ImageUtil.mergeBitmaps(bitmap1, bitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor)
        } finally {
            bitmap1.recycle()
            bitmap2.recycle()
        }
    }

    private fun enhancementConfigHash(): String {
        return ImageEnhancementCache.getConfigHash(
            noise = readerPreferences.realCuganNoiseLevel().get(),
            scale = readerPreferences.realCuganScale().get(),
            model = readerPreferences.realCuganModel().get(),
            realEsrganStyle = readerPreferences.realEsrganStyle().get(),
            maxWidth = readerPreferences.realCuganMaxSizeWidth().get(),
            maxHeight = readerPreferences.realCuganMaxSizeHeight().get(),
            skipMaxWidth = readerPreferences.realCuganSkipMaxSizeWidth().get(),
            skipMaxHeight = readerPreferences.realCuganSkipMaxSizeHeight().get(),
            tileSize = readerPreferences.realCuganTileSize().get(),
            precision = readerPreferences.realCuganPrecision().get(),
            fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
            processingBackend = readerPreferences.realCuganProcessingBackend().get(),
        )
    }

    private fun decodeEnhancedBitmap(file: File): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    // --- Two-line enhancement status (first page on top, second page below) ---

    private var enhancementStatusContainer: LinearLayout? = null
    private var enhancementStatusFirst: TextView? = null
    private var enhancementStatusSecond: TextView? = null

    private fun ensureEnhancementStatusViews(): Pair<TextView, TextView>? {
        if (enhancementStatusContainer == null) {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                elevation = 20f
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply {
                    setMargins(20, 0, 0, 20)
                }
                addView(makeEnhancementStatusTextView())
                addView(makeEnhancementStatusTextView())
            }
            addView(container)
            container.bringToFront()
            enhancementStatusContainer = container
            enhancementStatusFirst = container.getChildAt(0) as TextView
            enhancementStatusSecond = container.getChildAt(1) as TextView
        }
        val first = enhancementStatusFirst ?: return null
        val second = enhancementStatusSecond ?: return null
        return first to second
    }

    private fun makeEnhancementStatusTextView(): OutlineTextView = OutlineTextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun hideEnhancementStatus() {
        enhancementStatusJob?.cancel()
        enhancementStatusJob = null
        enhancementStatusContainer?.isVisible = false
    }

    private fun startEnhancementStatusTracking() {
        enhancementStatusJob?.cancel()
        val second = extraPage ?: return
        val mId = page.chapter.chapter.manga_id ?: -1L
        val cId = page.chapter.chapter.id ?: -1L
        if (mId == -1L || cId == -1L) return
        val views = ensureEnhancementStatusViews() ?: return
        val (firstView, secondView) = views
        enhancementStatusJob = scope.launch {
            var attempts = 0
            // Cancellation is delivered via `delay`, so the loop exits on detach.
            while (attempts < 240) {
                val configHash = enhancementConfigHash()
                val firstStatus = computeEnhancementStatus(mId, cId, page.index, page.enhancementKeySuffix, configHash)
                val secondStatus = computeEnhancementStatus(mId, cId, second.index, second.enhancementKeySuffix, configHash)
                withUIContext {
                    firstView.text = firstStatus
                    firstView.isVisible = true
                    secondView.text = secondStatus
                    secondView.isVisible = true
                    enhancementStatusContainer?.let {
                        it.isVisible = true
                        it.bringToFront()
                    }
                }
                val firstDone = ImageEnhancementCache.getCachedImage(mId, cId, page.index, configHash, page.enhancementKeySuffix) != null ||
                    ImageEnhancementCache.isSkipped(mId, cId, page.index, configHash, page.enhancementKeySuffix)
                val secondDone = ImageEnhancementCache.getCachedImage(mId, cId, second.index, configHash, second.enhancementKeySuffix) != null ||
                    ImageEnhancementCache.isSkipped(mId, cId, second.index, configHash, second.enhancementKeySuffix)
                if (firstDone && secondDone) {
                    // Both pages finished: keep showing the final status (no stay delay, no hide).
                    return@launch
                }
                delay(500)
                attempts++
            }
        }
    }

    private fun computeEnhancementStatus(mId: Long, cId: Long, index: Int, variant: String, configHash: String): String {
        return when {
            ImageEnhancementCache.getCachedImage(mId, cId, index, configHash, variant) != null ->
                context.stringResource(KMR.strings.reader_status_processed)
            ImageEnhancementCache.isSkipped(mId, cId, index, configHash, variant) ->
                context.stringResource(KMR.strings.reader_status_raw)
            Waifu2x.getProgressId() == index -> {
                val rawProgress = Waifu2x.getProgressPercent()
                if (rawProgress in 0..100) {
                    context.stringResource(KMR.strings.reader_status_enhancing_progress, rawProgress)
                } else {
                    context.stringResource(KMR.strings.reader_status_enhancing) + ".".repeat(
                        (rawProgress % 3).let { if (it < 0) -it else it } + 1,
                    )
                }
            }
            ImageEnhancer.hasRequest(mId, cId, index, variant) ->
                context.stringResource(KMR.strings.reader_status_queued)
            else -> context.stringResource(KMR.strings.reader_status_processing)
        }
    }

    /**
     * A simple [TextView] that draws a black outline around its text so the status stays
     * readable over bright manga pages.
     */
    private class OutlineTextView(context: Context) : TextView(context) {
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.BLACK
        }

        override fun onDraw(canvas: Canvas) {
            val text = text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                outlinePaint.typeface = paint.typeface
                outlinePaint.textSize = paint.textSize
                outlinePaint.textAlign = paint.textAlign
                canvas.drawText(text, compoundPaddingLeft.toFloat(), baseline.toFloat(), outlinePaint)
            }
            super.onDraw(canvas)
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun mergePages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource {
        // Handle adding a center margin to wide images if requested
        if (imageSource2 == null) {
            return handleWideImage(imageSource)
        }

        if (page.fullPage) return imageSource
        if (ImageUtil.isAnimatedAndSupported(imageSource)) {
            page.fullPage = true
            splitDoublePages()
            return imageSource
        } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap = decodeImage(imageSource)
        if (imageBitmap == null) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(96) }
        if (imageBitmap.height < imageBitmap.width) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap2 = decodeImage(imageSource2)
        if (imageBitmap2 == null) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(97) }
        if (imageBitmap2.height < imageBitmap2.width) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageSource
        }

        val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
        val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

        imageSource.close()
        imageSource2.close()

        return ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor) {
            updateProgress(it)
        }
    }

    private fun handleWideImage(imageSource: BufferedSource): BufferedSource {
        return if (
            !ImageUtil.isAnimatedAndSupported(imageSource) &&
            ImageUtil.isWideImage(imageSource) &&
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
        } else {
            imageSource
        }
    }

    private fun decodeImage(imageSource: BufferedSource): Bitmap? {
        return try {
            ImageDecoder.newInstance(imageSource.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot decode image" }
            null
        }
    }

    private fun calculateCenterMargin(height: Int, height2: Int): Int {
        return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private fun updateProgress(progress: Int) {
        scope.launch {
            if (progress == 100) {
                progressIndicator?.hide()
            } else {
                progressIndicator?.setProgress(progress)
            }
        }
    }

    private fun splitDoublePages() {
        scope.launch {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
            0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
