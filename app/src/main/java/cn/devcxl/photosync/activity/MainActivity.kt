package cn.devcxl.photosync.activity

import kotlin.math.roundToInt

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewpager2.widget.ViewPager2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.App
import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.FilmstripAdapter
import cn.devcxl.photosync.adapter.JpegSourceInfo
import cn.devcxl.photosync.adapter.PhotoContentVersion
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.adapter.PhotoRenderState
import cn.devcxl.photosync.adapter.PhotoViewerMode
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.EditorLaunchArgs
import cn.devcxl.photosync.editor.contract.GallerySink
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.export.BakeExporter
import cn.devcxl.photosync.editor.export.BakeRequest
import cn.devcxl.photosync.editor.export.BakeResult
import cn.devcxl.photosync.editor.export.ExifCopier
import cn.devcxl.photosync.editor.ui.EditorActivity
import cn.devcxl.photosync.export.MediaStoreGallerySink
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import cn.devcxl.photosync.ptp.manager.cameraDeleteBlockedBy
import cn.devcxl.photosync.utils.ExtensionUtils
import cn.devcxl.photosync.wrapper.RawWrapper

class MainActivity : AppCompatActivity() {

    private val connectionController by lazy {
        (application as App).usbPtpConnectionController
    }

    private val gallerySink: GallerySink by lazy {
        MediaStoreGallerySink(contentResolver)
    }

    companion object {
        private const val REQ_WRITE_STORAGE = 2001
        private const val THUMBNAIL_MAX_EDGE_PX = 512
        private const val RAW_PREVIEW_MAX_EDGE_PX = 1200
        private const val FULL_PREVIEW_SCALE_FACTOR = 2
        private const val FULL_PREVIEW_MAX_EDGE_PX = 4096
        private const val JPEG_MIME = GallerySink.JPEG_MIME
        private const val JPEG_EXPORT_QUALITY = 95
        private const val PREFS_NAME = "photosync_prefs"
        private const val PREF_DELETE_ON_CAMERA = "delete_on_camera"
        private const val MENU_EXPORT_BAKED = 1
        private const val MENU_EXPORT_ORIGINAL = 2
        private const val EXPORT_OVERLAY_FADE_MS = 180L
        private val exportDateFormat = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
    }

    private lateinit var adapter: PhotoPagerAdapter
    private lateinit var filmstripAdapter: FilmstripAdapter
    private lateinit var darkroom: TetheredDarkroomCoordinator
    private val originalPreviewCache = OriginalPreviewCache()
    private var pendingExportIndex: Int? = null
    private var pendingExportBake: Boolean = false

    private lateinit var binding: ActivityMainBinding

    private lateinit var dao: PhotoDao
    private val knownPhotoIds = mutableSetOf<Long>()
    private var photoListInitialized: Boolean = false

    private var connectionState: UsbPtpConnectionState = UsbPtpConnectionState.Idle
    private var cameraPresent: Boolean = false
    private var userImmersive: Boolean = false
    private var currentPageZoomed: Boolean = false
    private var infoDrawerOpen: Boolean = false
    private var infoTargetPath: String? = null
    private var autoFollowEnabled: Boolean = true
    private var pendingNewPhotoCount: Int = 0
    private var filmstripCollapsed: Boolean = false
    private var lastBottomInsetPx: Int = 0
    @Volatile
    private var editedPhotoMap: Map<Long, PhotoEditRecord> = emptyMap()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideFeedbackRunnable = Runnable { hideInlineFeedback() }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val editedPath = result.data?.getStringExtra(EditorActivity.EXTRA_SOURCE_PATH)
        if (!editedPath.isNullOrBlank()) {
            invalidateCachedBitmapsForPath(editedPath)
        }
    }

    private val thumbnailCache: LruCache<String, Bitmap> by lazy { createBitmapCache(16) }
    private val fullBitmapCache: LruCache<String, Bitmap> by lazy { createBitmapCache(8) }
    private val thumbnailDecodes = PreviewTaskRegistry()
    private val fullPreviewDecodes = PreviewTaskRegistry()
    private val jpegSourceInfoCache = Collections.synchronizedMap(mutableMapOf<String, JpegSourceInfo>())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawThumbnailDispatcher = Dispatchers.Default.limitedParallelism(1)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawExportDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val previewLongEdgePx: Int by lazy {
        maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .coerceAtLeast(1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()
        setupDao()
        setupAdapter()
        setupViewPager()
        setupFilmstrip()
        setupTetheredDarkroom()
        observeDatabase()
        observeConnectionState()
        setupButtons()
        configureConnectionController()
        applySystemBarVisibility(showOverlays = true)
        connectionController.refreshAvailableCameras()
    }

    /**
     * Wires the quick preset strip, the batch bake card and the press-and-hold compare.
     *
     * Must run after the adapters exist: the strip's callbacks read the pager's current graph, and the
     * compare controller pushes bitmaps into the pager.
     */
    private fun setupTetheredDarkroom() {
        darkroom = TetheredDarkroomCoordinator(
            context = this,
            binding = binding,
            owner = this,
            scope = lifecycleScope,
            dao = dao,
            adapter = adapter,
            originalPreviewCache = originalPreviewCache,
            currentIndex = { binding.viewPager.currentItem },
            editedMap = { editedPhotoMap },
            decodeOriginal = ::decodeOriginalPreview,
            onEditPersisted = ::onEditPersisted,
            onFeedback = { message -> showInlineFeedback(message) }
        ).also { it.bind() }
        darkroom.observeTransferState()
    }

    /** Re-reads the edited record of [photoId] and repaints the pager, filmstrip and strip. */
    private fun onEditPersisted(photoId: Long) {
        val path = findPathByPhotoId(photoId)
        if (path != null) {
            invalidateCachedBitmapsForPath(path)
        }
        darkroom.refreshQuickStrip()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val show = shouldShowOverlays(
                hasPhotos = adapter.itemCount > 0,
                userImmersive = userImmersive,
                isZoomed = currentPageZoomed
            )
            applySystemBarVisibility(showOverlays = show)
        }
    }

    private fun setupWindowInsets() {
        val safeChromeType =
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.navigationBars()

        val topBaseLeft = binding.topOverlayBar.paddingLeft
        val topBaseTop = binding.topOverlayBar.paddingTop
        val topBaseRight = binding.topOverlayBar.paddingRight
        val topBaseBottom = binding.topOverlayBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.topOverlayBar) { view, insets ->
            val safe = insets.getInsets(safeChromeType)
            view.updatePadding(
                left = topBaseLeft + safe.left,
                top = topBaseTop + safe.top,
                right = topBaseRight + safe.right,
                bottom = topBaseBottom
            )
            insets
        }

        val emptyBaseLeft = binding.emptyStateContainer.paddingLeft
        val emptyBaseRight = binding.emptyStateContainer.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(binding.emptyStateContainer) { view, insets ->
            val safe = insets.getInsets(safeChromeType)
            view.updatePadding(
                left = emptyBaseLeft + safe.left,
                top = safe.top,
                right = emptyBaseRight + safe.right,
                bottom = safe.bottom
            )
            insets
        }

        val zoomBaseStart = (binding.zoomBadge.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        val zoomBaseBottom = (binding.zoomBadge.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.zoomBadge) { view, insets ->
            val safe = insets.getInsets(safeChromeType)
            val lp = view.layoutParams as ViewGroup.MarginLayoutParams
            lp.marginStart = zoomBaseStart + safe.left
            lp.bottomMargin = zoomBaseBottom + safe.bottom
            view.layoutParams = lp
            insets
        }

        val transferBaseEnd =
            (binding.transferStatusCard.layoutParams as ViewGroup.MarginLayoutParams).marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.transferStatusCard) { view, insets ->
            val safe = insets.getInsets(safeChromeType)
            val lp = view.layoutParams as ViewGroup.MarginLayoutParams
            lp.marginEnd = transferBaseEnd + safe.right
            view.layoutParams = lp
            insets
        }

        binding.topOverlayBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.infoDrawerPanel.post { alignInfoDrawerToToggle() }
        }

        val bannerBaseBottom =
            (binding.newPhotoBanner.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.newPhotoBanner) { _, insets ->
            val safe = insets.getInsets(safeChromeType)
            lastBottomInsetPx = safe.bottom
            updateNewPhotoBannerMargin(bannerBaseBottom)
            insets
        }

        val filmBaseLeft = binding.bottomFilmstripContainer.paddingLeft
        val filmBaseTop = binding.bottomFilmstripContainer.paddingTop
        val filmBaseRight = binding.bottomFilmstripContainer.paddingRight
        val filmBaseBottom = binding.bottomFilmstripContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomFilmstripContainer) { view, insets ->
            val safe = insets.getInsets(safeChromeType)
            view.updatePadding(
                left = filmBaseLeft + safe.left,
                top = filmBaseTop,
                right = filmBaseRight + safe.right,
                bottom = filmBaseBottom + safe.bottom
            )
            insets
        }
    }

    private fun updateNewPhotoBannerMargin(baseMarginPx: Int = resources.getDimensionPixelSize(R.dimen.banner_margin_default)) {
        val lp = binding.newPhotoBanner.layoutParams as ViewGroup.MarginLayoutParams
        val bottomVisible = binding.bottomFilmstripContainer.visibility == View.VISIBLE
        lp.bottomMargin = if (bottomVisible) baseMarginPx else baseMarginPx + lastBottomInsetPx
        binding.newPhotoBanner.layoutParams = lp
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            connectionController.refreshAvailableCameras()
        }
    }

    override fun onResume() {
        super.onResume()
        // Attach broadcasts can be missed while the app is not running; the button state
        // is derived from what is actually plugged in.
        connectionController.refreshAvailableCameras()
    }

    private fun setupDao() {
        dao = AppDatabase.getInstance(this).photoDao()
    }

    private fun setupAdapter() {
        adapter = PhotoPagerAdapter(
            renderStateProvider = { path -> renderStateForPath(path) },
            onBindRequest = { path, position ->
                ensurePhotoForPosition(path, position)
            },
            jpegSourceInfoProvider = { path -> getJpegSourceInfo(path) },
            isCurrentPageProvider = { position -> position == binding.viewPager.currentItem },
            isJpegProvider = { path ->
                val entity = entityForPath(path)
                entity != null &&
                    resolveGalleryViewerMode(entity, editedPhotoMap, isJpegPath(path)) ==
                    PhotoViewerMode.JPEG_TILED
            },
            onPhotoScaleChanged = { _, position, scale, minScale ->
                onCurrentPhotoScaleChanged(position, scale, minScale)
            },
            onPhotoTap = {
                onPhotoCanvasTap()
            }
        )
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = adapter
        (binding.viewPager.getChildAt(0) as? RecyclerView)?.let { recyclerView ->
            (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            recyclerView.itemAnimator = null
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                darkroom.endCompare()
                currentPageZoomed = false
                binding.zoomBadge.visibility = View.GONE
                if (position >= adapter.itemCount - 1) {
                    pendingNewPhotoCount = 0
                }
                adapter.updatePrimaryPosition(position)
                filmstripAdapter.updateSelectedPosition(position)
                if (position in 0 until filmstripAdapter.itemCount) {
                    binding.filmstripRecyclerView.smoothScrollToPosition(position)
                }
                prefetchAround(position)
                renderPhotoChrome()
                renderNewPhotoBanner()
                updateOverlaysVisibility()
                darkroom.refreshQuickStrip(darkroomGraphFor(position))
            }
        })
    }

    /**
     * Graph of the page at [position], as the quick strip needs it.
     *
     * Reading through the edited map keeps the strip in sync with the pager without a second store
     * query per swipe.
     */
    private fun darkroomGraphFor(position: Int): EditGraph {
        val entity = adapter.getItem(position) ?: return EditGraph()
        val json = editedPhotoMap[entity.id]?.graphJson ?: return EditGraph()
        return EditGraphJsonCodec.decode(json) ?: EditGraph()
    }

    private fun setupFilmstrip() {
        filmstripAdapter = FilmstripAdapter(
            thumbnailProvider = { path -> cachedThumbnailForPath(path) },
            onThumbnailRequest = { path -> ensureThumbnail(path) },
            onItemClick = { position ->
                if (position in 0 until adapter.itemCount) {
                    binding.viewPager.setCurrentItem(position, true)
                }
            },
            hasEditPreviewProvider = { photoId -> editedPhotoMap.containsKey(photoId) }
        )
        binding.filmstripRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = filmstripAdapter
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun onPhotoCanvasTap() {
        if (adapter.itemCount == 0) return
        userImmersive = !userImmersive
        updateOverlaysVisibility()
    }

    private fun onCurrentPhotoScaleChanged(position: Int, scale: Float, minScale: Float) {
        if (position != binding.viewPager.currentItem) return
        val zoomed = isZoomedScale(scale, minScale)
        if (zoomed) {
            val ratio = resolveZoomRatio(scale, minScale)
            binding.zoomBadge.text = formatZoomBadge(ratio)
            binding.zoomBadge.visibility = View.VISIBLE
        } else {
            binding.zoomBadge.visibility = View.GONE
        }
        if (currentPageZoomed != zoomed) {
            currentPageZoomed = zoomed
            updateOverlaysVisibility()
        }
    }

    private fun updateOverlaysVisibility() {
        val hasPhotos = adapter.itemCount > 0
        if (!hasPhotos) {
            userImmersive = false
            currentPageZoomed = false
            binding.zoomBadge.visibility = View.GONE
        }
        val show = shouldShowOverlays(
            hasPhotos = hasPhotos,
            userImmersive = userImmersive,
            isZoomed = currentPageZoomed
        )
        animateTopOverlay(show)
        animateBottomFilmstrip(shouldShowFilmstrip(hasPhotos, show))
        val hasDrawer = show && infoDrawerOpen && hasPhotos
        if (hasDrawer) {
            alignInfoDrawerToToggle()
        }
        binding.infoDrawerPanel.visibility = if (hasDrawer) View.VISIBLE else View.GONE
        renderNewPhotoBanner()
        applySystemBarVisibility(showOverlays = show)
    }

    private fun applySystemBarVisibility(showOverlays: Boolean) {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        if (showOverlays) {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun animateBottomFilmstrip(show: Boolean) {
        val bar = binding.bottomFilmstripContainer
        bar.animate().cancel()
        if (show) {
            updateFilmstripCollapseState()
            if (bar.visibility != View.VISIBLE) {
                bar.alpha = 0f
                bar.translationY = 24f
                bar.visibility = View.VISIBLE
            }
            bar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .withEndAction(null)
                .start()
        } else if (bar.visibility == View.VISIBLE) {
            bar.animate()
                .alpha(0f)
                .translationY(24f)
                .setDuration(180)
                .withEndAction {
                    bar.visibility = View.GONE
                    updateNewPhotoBannerMargin()
                }
                .start()
        }
        updateNewPhotoBannerMargin()
    }

    private fun updateFilmstripCollapseState() {
        binding.filmstripRecyclerView.visibility =
            if (filmstripCollapsed) View.GONE else View.VISIBLE
        binding.filmstripToggleHandle.setText(
            if (filmstripCollapsed) R.string.filmstrip_expand else R.string.filmstrip_collapse
        )
    }

    private fun animateTopOverlay(show: Boolean) {
        val bar = binding.topOverlayBar
        bar.animate().cancel()
        if (show) {
            if (bar.visibility != View.VISIBLE) {
                bar.alpha = 0f
                bar.translationY = -20f
                bar.visibility = View.VISIBLE
            }
            bar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .withEndAction(null)
                .start()
        } else if (bar.visibility == View.VISIBLE) {
            bar.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(180)
                .withEndAction { bar.visibility = View.GONE }
                .start()
        }
    }

    private fun showInlineFeedback(message: String) {
        mainHandler.removeCallbacks(hideFeedbackRunnable)
        val pill = binding.inlineFeedbackPill
        pill.animate().cancel()
        pill.text = message
        if (pill.visibility != View.VISIBLE) {
            pill.alpha = 0f
            pill.visibility = View.VISIBLE
        }
        pill.animate().alpha(1f).setDuration(160).withEndAction(null).start()
        mainHandler.postDelayed(hideFeedbackRunnable, 2200L)
    }

    private fun hideInlineFeedback() {
        val pill = binding.inlineFeedbackPill
        if (pill.visibility != View.VISIBLE) return
        pill.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { pill.visibility = View.GONE }
            .start()
    }

    private fun observeDatabase() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAllFlow().collect { list ->
                    val previousCount = adapter.itemCount
                    val previousIndex = binding.viewPager.currentItem
                    val itemsCopy = list.toList()
                    adapter.updateItems(itemsCopy)
                    filmstripAdapter.updateItems(itemsCopy)
                    evictCachesForRemovedItems(list)
                    val newlyAdded = if (!photoListInitialized) {
                        knownPhotoIds.addAll(list.map { it.id })
                        photoListInitialized = true
                        emptyList()
                    } else {
                        val added = list.filter { it.id !in knownPhotoIds }
                        knownPhotoIds.clear()
                        knownPhotoIds.addAll(list.map { it.id })
                        added
                    }
                    if (newlyAdded.isNotEmpty()) {
                        val targetIdx = list.indexOfLast { it.id == newlyAdded.last().id }
                        val wasLatest = isViewingLatestPhoto(previousIndex, previousCount)
                        if (targetIdx >= 0 && shouldAutoAdvanceOnNewPhoto(autoFollowEnabled, currentPageZoomed, wasLatest)) {
                            binding.viewPager.setCurrentItem(targetIdx, previousCount > 0)
                            pendingNewPhotoCount = 0
                        } else {
                            pendingNewPhotoCount += newlyAdded.size
                        }
                    }
                    if (list.isNotEmpty()) {
                        val currentIndex = binding.viewPager.currentItem.coerceIn(0, list.size - 1)
                        adapter.updatePrimaryPosition(currentIndex)
                        filmstripAdapter.updateSelectedPosition(currentIndex)
                        if (previousCount == 0) {
                            binding.filmstripRecyclerView.scrollToPosition(currentIndex)
                        } else {
                            binding.filmstripRecyclerView.smoothScrollToPosition(currentIndex)
                        }
                        prefetchAround(currentIndex)
                    } else {
                        pendingNewPhotoCount = 0
                    }
                    renderPhotoChrome()
                    renderNewPhotoBanner()
                    updateOverlaysVisibility()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EditorHostRegistry.editStore?.observeEditedPhotoMap()?.collect { newMap ->
                    val oldMap = editedPhotoMap
                    editedPhotoMap = newMap
                    val changedPhotoIds = (oldMap.keys + newMap.keys).filter { id ->
                        oldMap[id] != newMap[id]
                    }
                    for (photoId in changedPhotoIds) {
                        val path = findPathByPhotoId(photoId)
                        if (path != null) {
                            invalidateCachedBitmapsForPath(path)
                        }
                    }
                    renderPhotoChrome()
                    if (adapter.itemCount > 0) {
                        darkroom.refreshQuickStrip(darkroomGraphFor(binding.viewPager.currentItem))
                    }
                }
            }
        }
    }

    private fun renderNewPhotoBanner() {
        val visible = pendingNewPhotoCount > 0 && adapter.itemCount > 0 && !userImmersive
        if (visible) {
            binding.newPhotoBanner.text =
                getString(R.string.new_photo_banner_count, pendingNewPhotoCount)
            updateNewPhotoBannerMargin()
            binding.newPhotoBanner.visibility = View.VISIBLE
        } else {
            binding.newPhotoBanner.visibility = View.GONE
        }
    }

    private fun updateAutoFollowAppearance() {
        binding.autoFollowToggleButton.setBackgroundResource(
            if (autoFollowEnabled) R.drawable.bg_toggle_pill_on else R.drawable.bg_toggle_pill
        )
        val tintColor = ContextCompat.getColor(
            this,
            if (autoFollowEnabled) R.color.toggle_on_text else R.color.darkroom_text_secondary
        )
        binding.autoFollowIcon.setColorFilter(tintColor)
        binding.autoFollowLabel.setTextColor(tintColor)
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var lastState: UsbPtpConnectionState? = null
                    connectionController.connectionState.collect { state ->
                        connectionState = state
                        renderUsbConnectionState(state, lastState)
                        lastState = state
                        renderConnectButton()
                        renderEmptyState()
                        renderInfoDrawer()
                    }
                }
                launch {
                    connectionController.availableCameras.collect { cameras ->
                        cameraPresent = cameras.isNotEmpty()
                        renderConnectButton()
                        renderEmptyState()
                    }
                }
            }
        }
    }

    private fun renderConnectButton() {
        val ui = resolveConnectPillUi(connectionState, cameraPresent)
        val style = resolveConnectPillStyle(ui.visualState)
        binding.connectButtonLabel.setText(ui.label)
        binding.connectButtonLabel.setTextColor(ContextCompat.getColor(this, style.textColorRes))
        binding.connectButton.isEnabled = ui.enabled
        binding.connectButton.setBackgroundResource(style.backgroundRes)
        binding.connectButton.alpha = if (ui.enabled || ui.visualState == ConnectPillVisualState.BUSY) {
            1.0f
        } else {
            0.78f
        }
        binding.connectStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, style.dotColorRes))
    }

    private fun renderEmptyState() {
        val isEmpty = adapter.itemCount == 0
        binding.emptyStateContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (!isEmpty) return

        val ui = resolveEmptyStateUi(connectionState, cameraPresent)
        binding.emptyTitleText.setText(ui.title)
        binding.emptyDescText.setText(ui.description)
        if (ui.actionLabel != null) {
            binding.emptyActionButton.setText(ui.actionLabel)
            binding.emptyActionButton.isEnabled = ui.actionEnabled
            binding.emptyActionButton.visibility = View.VISIBLE
        } else {
            binding.emptyActionButton.visibility = View.GONE
        }
    }

    private fun renderPhotoChrome() {
        val count = adapter.itemCount
        val hasPhotos = count > 0
        binding.photoMetaPill.visibility = if (hasPhotos) View.VISIBLE else View.GONE
        binding.infoToggleButton.visibility = if (hasPhotos) View.VISIBLE else View.GONE
        binding.topBarSpacer.visibility = if (hasPhotos) View.GONE else View.VISIBLE
        binding.editButton.isEnabled = hasPhotos
        binding.editButton.alpha = if (hasPhotos) 1.0f else 0.35f
        binding.exportButton.isEnabled = hasPhotos
        binding.exportButton.alpha = if (hasPhotos) 1.0f else 0.35f
        binding.deleteButton.isEnabled = hasPhotos
        binding.deleteButton.alpha = if (hasPhotos) 1.0f else 0.35f
        renderEmptyState()

        if (!hasPhotos) {
            infoDrawerOpen = false
            updateInfoToggleAppearance()
            updateOverlaysVisibility()
            return
        }

        val index = binding.viewPager.currentItem.coerceIn(0, count - 1)
        val item = adapter.getItem(index) ?: return
        binding.photoCounterText.text = formatPhotoCounter(index, count)

        val exported = isPhotoExported(item.uriString)
        val edited = isPhotoMarkedEdited(item, editedPhotoMap)
        binding.photoEditedBadge.visibility = if (edited) View.VISIBLE else View.GONE
        binding.photoExportedBadge.visibility = if (exported) View.VISIBLE else View.GONE
        if (binding.exportButtonSpinner.visibility != View.VISIBLE) {
            if (exported) {
                binding.exportButton.setBackgroundResource(R.drawable.bg_glass_icon_btn_exported)
                binding.exportButton.setImageResource(R.drawable.ic_check_24)
                ImageViewCompat.setImageTintList(
                    binding.exportButton,
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.exported_text))
                )
            } else {
                binding.exportButton.setBackgroundResource(R.drawable.bg_glass_icon_btn)
                binding.exportButton.setImageResource(R.drawable.ic_export_24)
                ImageViewCompat.setImageTintList(
                    binding.exportButton,
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.darkroom_text_primary))
                )
            }
        }
        updateInfoToggleAppearance()
        renderInfoDrawer()
    }

    private fun updateInfoToggleAppearance() {
        binding.infoToggleButton.setBackgroundResource(
            if (infoDrawerOpen) R.drawable.bg_toggle_pill_on else R.drawable.bg_glass_icon_btn
        )
        val colorRes = if (infoDrawerOpen) {
            R.color.toggle_on_text
        } else {
            R.color.darkroom_text_secondary
        }
        binding.infoToggleButton.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun alignInfoDrawerToToggle() {
        if (binding.infoToggleButton.visibility != View.VISIBLE || binding.topOverlayBar.width == 0) {
            return
        }
        val gapPx = resources.getDimensionPixelSize(R.dimen.info_drawer_top_gap)
        val lp = binding.infoDrawerPanel.layoutParams as ViewGroup.MarginLayoutParams
        val targetTop = binding.topOverlayBar.top + binding.infoToggleButton.bottom + gapPx
        val targetEnd = (binding.topOverlayBar.width - binding.infoToggleButton.right).coerceAtLeast(0)
        if (lp.topMargin != targetTop || lp.marginEnd != targetEnd) {
            lp.topMargin = targetTop
            lp.marginEnd = targetEnd
            binding.infoDrawerPanel.layoutParams = lp
        }
    }

    private fun renderInfoDrawer() {
        val count = adapter.itemCount
        if (count == 0 || !infoDrawerOpen) {
            binding.infoDrawerPanel.visibility = View.GONE
            return
        }
        val showOverlays = shouldShowOverlays(
            hasPhotos = true,
            userImmersive = userImmersive,
            isZoomed = currentPageZoomed
        )
        alignInfoDrawerToToggle()
        binding.infoDrawerPanel.visibility = if (showOverlays) View.VISIBLE else View.GONE
        val index = binding.viewPager.currentItem.coerceIn(0, count - 1)
        val item = adapter.getItem(index) ?: return
        infoTargetPath = item.path

        val handleText = when (
            resolveCameraHandleAvailability(
                objectHandle = item.objectHandle,
                isConnected = connectionState is UsbPtpConnectionState.Connected,
                cameraSupportsDelete = connectionController.canDeleteOnCamera()
            )
        ) {
            CameraHandleAvailability.AVAILABLE ->
                getString(R.string.info_handle_available, item.objectHandle)
            CameraHandleAvailability.NOT_CONNECTED ->
                getString(R.string.info_handle_not_connected, item.objectHandle)
            CameraHandleAvailability.NOT_SUPPORTED ->
                getString(R.string.info_handle_no_camera_support, item.objectHandle)
            CameraHandleAvailability.MISSING ->
                getString(R.string.info_handle_missing)
        }
        binding.infoHandleText.text = handleText

        val formatBadge = resolvePhotoFormatBadge(item.path)
        binding.infoFileNameText.text = item.name ?: getString(R.string.photo_default_name)
        when (formatBadge) {
            PhotoFormatBadge.RAW -> {
                binding.infoFormatBadge.visibility = View.VISIBLE
                binding.infoFormatBadge.setText(R.string.format_raw)
                binding.infoFormatBadge.setBackgroundResource(R.drawable.bg_badge_raw)
                binding.infoFormatBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.badge_raw_text)
                )
            }
            PhotoFormatBadge.JPEG -> {
                binding.infoFormatBadge.visibility = View.VISIBLE
                binding.infoFormatBadge.setText(R.string.format_jpeg)
                binding.infoFormatBadge.setBackgroundResource(R.drawable.bg_badge_jpg)
                binding.infoFormatBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.badge_jpg_text)
                )
            }
            PhotoFormatBadge.OTHER -> {
                binding.infoFormatBadge.visibility = View.GONE
            }
        }
        binding.infoResolutionText.text = getString(R.string.info_unknown_value)
        binding.infoFormatSizeText.text = formatPhotoFormatAndSize(formatBadge, 0L)

        val targetPath = item.path
        lifecycleScope.launch(Dispatchers.IO) {
            val sizeBytes = File(targetPath).let { if (it.exists()) it.length() else 0L }
            val jpegBounds = getJpegSourceInfo(targetPath)
            val fallbackBmp = if (jpegBounds == null) {
                fullBitmapCache.get(targetPath) ?: thumbnailCache.get(targetPath)
            } else null
            val resText = formatPhotoDimensions(
                width = jpegBounds?.width ?: fallbackBmp?.width,
                height = jpegBounds?.height ?: fallbackBmp?.height
            )
            val sizeText = formatPhotoFormatAndSize(formatBadge, sizeBytes)
            withContext(Dispatchers.Main) {
                if (infoTargetPath == targetPath) {
                    binding.infoResolutionText.text = resText
                    binding.infoFormatSizeText.text = sizeText
                }
            }
        }
    }

    private fun setupButtons() {
        binding.editButton.setOnClickListener { openEditorForCurrentPhoto() }
        binding.exportButton.setOnClickListener { exportCurrent() }
        binding.exportButton.setOnLongClickListener {
            showExportMenu()
            true
        }
        binding.deleteButton.setOnClickListener { showDeleteConfirmDialog() }
        binding.connectButton.setOnClickListener { onConnectButtonClick() }
        binding.emptyActionButton.setOnClickListener {
            connectionController.connectToAvailableCamera()
        }
        binding.infoToggleButton.setOnClickListener {
            infoDrawerOpen = !infoDrawerOpen
            updateInfoToggleAppearance()
            renderInfoDrawer()
        }
        binding.autoFollowToggleButton.setOnClickListener {
            autoFollowEnabled = !autoFollowEnabled
            updateAutoFollowAppearance()
            val msgRes = if (autoFollowEnabled) {
                R.string.feedback_auto_follow_on
            } else {
                R.string.feedback_auto_follow_off
            }
            showInlineFeedback(getString(msgRes))
        }
        binding.newPhotoBanner.setOnClickListener {
            if (adapter.itemCount > 0) {
                binding.viewPager.setCurrentItem(adapter.itemCount - 1, true)
            }
            pendingNewPhotoCount = 0
            renderNewPhotoBanner()
        }
        binding.filmstripToggleHandle.setOnClickListener {
            filmstripCollapsed = !filmstripCollapsed
            updateFilmstripCollapseState()
        }
        updateAutoFollowAppearance()
    }

    private fun onConnectButtonClick() {
        when (resolveConnectPillAction(connectionState, cameraPresent)) {
            ConnectPillAction.CONFIRM_DISCONNECT -> showDisconnectConfirmDialog()
            ConnectPillAction.CONNECT -> connectionController.connectToAvailableCamera()
            ConnectPillAction.NONE -> Unit
        }
    }

    private fun showDisconnectConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_disconnect_title)
            .setMessage(R.string.dialog_disconnect_message)
            .setPositiveButton(R.string.dialog_disconnect_confirm) { _, _ ->
                connectionController.disconnect("user_request")
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideFeedbackRunnable)
        connectionController.setFileTransferListener(null)
        // The download sink belongs to `App`, not to this activity: clearing it here would stop the
        // tethered ingest pipeline as soon as the gallery is recreated.
        // The session belongs to the process, not to this activity (docs/adr/0001):
        // a system-driven recreate must not tear down a healthy session, so only a real
        // exit releases the camera.
        if (isFinishing && !isChangingConfigurations) {
            connectionController.disconnect("activity_finished")
        }
        super.onDestroy()
    }

    private fun configureConnectionController() {
        connectionController.setFileTransferListener { _, totalByteLength, transterByteLength ->
            (application as App).notifyTransferStarted()
            val progressPercent = resolveTransferPercent(transterByteLength, totalByteLength)
            lifecycleScope.launch(Dispatchers.Main) {
                if (binding.transferStatusCard.visibility != View.VISIBLE) {
                    binding.transferStatusCard.visibility = View.VISIBLE
                }
                binding.transferPercentText.text =
                    getString(R.string.sync_transfer_percent, progressPercent)
                binding.progressBar.progress = progressPercent
            }
        }
        // The download sink belongs to `App`: an import that finishes while the editor is in front must
        // still be committed with its `LiveTetherPreset`.
    }

    /**
     * Reacts to photos `App` committed: dismisses the transfer card and reveals the newest one.
     *
     * Subscribing instead of installing a second `FileDownloadedListener` is what keeps the tethered
     * ingest single-owner — the listener had to move to `App` so a background sync still lands.
     */
    private fun renderUsbConnectionState(
        state: UsbPtpConnectionState,
        previousState: UsbPtpConnectionState?
    ) {
        when (state) {
            is UsbPtpConnectionState.Connected -> {
                if (previousState !is UsbPtpConnectionState.Connected) {
                    showInlineFeedback(getString(R.string.toast_device_connected))
                }
            }
            is UsbPtpConnectionState.Disconnected -> {
                (application as App).clearTransferActive()
                binding.transferStatusCard.visibility = View.GONE
                if (state.reason == "no_device" && previousState !is UsbPtpConnectionState.Disconnected) {
                    showInlineFeedback(getString(R.string.toast_no_usb_device))
                } else if (state.reason == "user_request" &&
                    previousState !is UsbPtpConnectionState.Disconnected
                ) {
                    showInlineFeedback(getString(R.string.feedback_camera_disconnected))
                }
            }
            is UsbPtpConnectionState.Error -> {
                (application as App).clearTransferActive()
                binding.transferStatusCard.visibility = View.GONE
                if (state.reason == "permission_denied") {
                    showInlineFeedback(getString(R.string.usb_permission_denied))
                } else if (previousState !is UsbPtpConnectionState.Error) {
                    showInlineFeedback(getString(resolveConnectionErrorLabel(state.reason)))
                }
                Timber.w("USB connection error: reason=%s detail=%s", state.reason, state.detail)
            }
            is UsbPtpConnectionState.PermissionRequested,
            is UsbPtpConnectionState.Connecting,
            is UsbPtpConnectionState.Disconnecting,
            UsbPtpConnectionState.Idle -> Unit
        }
    }

    private fun createBitmapCache(divisor: Int): LruCache<String, Bitmap> {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemKb / divisor).coerceAtLeast(1024)
        return object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    /**
     * Drops per-path cache entries whose photo is no longer present in the database.
     *
     * The bitmap caches are bounded [LruCache]s, but [jpegSourceInfoCache] is a plain
     * map, so it would keep one entry per imported path for the whole session without
     * this eviction.
     */
    private fun evictCachesForRemovedItems(currentItems: List<PhotoEntity>) {
        val livePaths = currentItems.mapTo(mutableSetOf()) { it.path }
        val removed = jpegSourceInfoCache.keys.filter { it !in livePaths }
        removed.forEach { originalPreviewCache.remove(it) }
        jpegSourceInfoCache.keys.retainAll(livePaths)
        if (currentItems.isEmpty()) originalPreviewCache.clear()
    }

    private fun ensurePhotoForPosition(path: String, position: Int) {
        val isCurrentPage = position == binding.viewPager.currentItem
        if (shouldLoadThumbnail(isCurrentPage, isRawPath(path))) {
            ensureThumbnail(path)
        }
        // The full preview is only decoded for the page on screen; pager settling
        // still reports the outgoing position, so neighbours stay thumbnail-only.
        if (isCurrentPage) {
            ensureFullPreview(path)
        }
    }

    private fun prefetchAround(position: Int) {
        val current = adapter.getItem(position) ?: return
        ensureThumbnail(current.path)

        val previous = adapter.getItem(position - 1)
        val next = adapter.getItem(position + 1)
        previous?.takeUnless { isRawPath(it.path) }?.let { ensureThumbnail(it.path) }
        next?.takeUnless { isRawPath(it.path) }?.let { ensureThumbnail(it.path) }
    }

    private fun getJpegSourceInfo(path: String): JpegSourceInfo? {
        if (!isJpegPath(path)) return null
        jpegSourceInfoCache[path]?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return JpegSourceInfo(options.outWidth, options.outHeight).also {
            jpegSourceInfoCache[path] = it
        }
    }

    private fun isJpegPath(path: String): Boolean {
        return ExtensionUtils.isJpegExtension(ExtensionUtils.fileExtension(path))
    }

    private fun isRawPath(path: String): Boolean {
        return ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(path))
    }

    /**
     * Resolves where a preview decode should read from.
     *
     * Must be called where the adapter list and [editedPhotoMap] are consistent (the main
     * thread); the decode coroutine then works from this immutable snapshot only.
     */
    private fun snapshotPreviewDecodeSource(path: String): PreviewDecodeSource {
        val decision = previewDisplayDecisionForPath(path)
        return PreviewDecodeSource(
            path = path,
            previewOverridePath = decision?.previewPath,
            isPreviewUnavailable = decision?.source == PreviewDisplaySource.UNAVAILABLE
        )
    }

    private fun ensureThumbnail(path: String) {
        val source = snapshotPreviewDecodeSource(path)
        if (source.isPreviewUnavailable) {
            // The record owns this page: decoding the original would be exactly the fallback the
            // edit is meant to hide, and cached bitmaps predate the current record.
            discardCachedPreview(path)
            return
        }
        if (thumbnailCache.get(path) != null || fullBitmapCache.get(path) != null) return
        val task = thumbnailDecodes.begin(path) ?: return
        val isRaw = isRawPath(path)
        val dispatcher = if (isRaw) rawThumbnailDispatcher else Dispatchers.IO
        lifecycleScope.launch(dispatcher) {
            try {
                if (isRaw) {
                    Timber.d("start RAW thumbnail decode: %s", path)
                }
                val bitmap = decodeThumbnailBitmap(source) ?: return@launch
                if (isRaw) {
                    Timber.d("decoded RAW thumbnail bitmap %dx%d: %s", bitmap.width, bitmap.height, path)
                }
                withContext(Dispatchers.Main) {
                    val published = thumbnailDecodes.publishIfCurrent(task) {
                        thumbnailCache.put(path, bitmap)
                    }
                    if (!published) return@withContext
                    adapter.notifyPathChanged(path)
                    filmstripAdapter.notifyPathChanged(path)
                    if (infoDrawerOpen && infoTargetPath == path) {
                        renderInfoDrawer()
                    }
                }
            } finally {
                thumbnailDecodes.release(task)
            }
        }
    }

    private fun ensureFullPreview(path: String) {
        val source = snapshotPreviewDecodeSource(path)
        if (source.isPreviewUnavailable) {
            discardCachedPreview(path)
            return
        }
        if (fullBitmapCache.get(path) != null) return
        if (isRawPath(path) && source.previewOverridePath == null) return
        val task = fullPreviewDecodes.begin(path) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeFullPreviewBitmap(source) ?: return@launch
                withContext(Dispatchers.Main) {
                    val published = fullPreviewDecodes.publishIfCurrent(task) {
                        fullBitmapCache.put(path, bitmap)
                    }
                    if (published) adapter.notifyPathChanged(path)
                }
            } finally {
                fullPreviewDecodes.release(task)
            }
        }
    }

    private fun decodeThumbnailBitmap(source: PreviewDecodeSource): Bitmap? {
        if (source.isPreviewUnavailable) return null
        val previewOverride = source.previewOverridePath
        if (previewOverride != null) {
            return decodeSampledBitmap(previewOverride, THUMBNAIL_MAX_EDGE_PX)
        }
        val path = source.path
        val ext = ExtensionUtils.fileExtension(path)
        return try {
            when {
                ExtensionUtils.isRawExtension(ext) -> {
                    val bmp = RawWrapper.decodeThumbnailBitmap(path, RAW_PREVIEW_MAX_EDGE_PX)
                    if (bmp == null) Timber.w("RAW thumbnail decode returned null: %s", path)
                    bmp
                }
                ExtensionUtils.isJpegExtension(ext) -> decodeSampledBitmap(path, THUMBNAIL_MAX_EDGE_PX)
                else -> {
                    Timber.w("unknown extension for thumbnail: ext=%s path=%s", ext, path)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "decodeThumbnailBitmap failed for path=%s", path)
            null
        }
    }

    private fun decodeFullPreviewBitmap(source: PreviewDecodeSource): Bitmap? {
        if (source.isPreviewUnavailable) return null
        val previewOverride = source.previewOverridePath
        if (previewOverride != null) {
            val targetEdge = (previewLongEdgePx * FULL_PREVIEW_SCALE_FACTOR)
                .coerceAtMost(FULL_PREVIEW_MAX_EDGE_PX)
            return decodeSampledBitmap(previewOverride, targetEdge)
        }
        val path = source.path
        val ext = ExtensionUtils.fileExtension(path)
        return try {
            when {
                ExtensionUtils.isRawExtension(ext) -> RawWrapper.decodeThumbnailBitmap(path)
                ExtensionUtils.isJpegExtension(ext) -> {
                    val targetEdge = (previewLongEdgePx * FULL_PREVIEW_SCALE_FACTOR)
                        .coerceAtMost(FULL_PREVIEW_MAX_EDGE_PX)
                    decodeSampledBitmap(path, targetEdge)
                }
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "decodeFullPreviewBitmap failed for path=%s", path)
            null
        }
    }

    private fun openEditorForCurrentPhoto() {
        if (adapter.itemCount == 0) return
        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        val entity = adapter.getItem(index) ?: return
        val args = EditorLaunchArgs(
            photoId = entity.id,
            sourcePath = entity.path,
            displayName = entity.name,
            isRaw = isRawPath(entity.path)
        )
        editorLauncher.launch(EditorActivity.createIntent(this, args))
    }

    /** Display decision for the library photo at [path], or `null` when it is not in the pager. */
    private fun previewDisplayDecisionForPath(path: String): PreviewDisplayDecision? =
        entityForPath(path)?.let { entity ->
            resolvePreviewDisplayDecision(entity, editedPhotoMap)
        }

    private fun entityForPath(path: String): PhotoEntity? {
        val index = adapter.indexOfPath(path)
        return if (index >= 0) adapter.getItem(index) else null
    }

    /**
     * Read state for one page.
     *
     * An unavailable preview must not read the bitmap caches: they may still hold the original or
     * a pre-edit preview decoded before the record changed. The explicit flag makes the pager clear
     * its surface and show the recovery message instead of either stale image.
     */
    private fun renderStateForPath(path: String): PhotoRenderState {
        val decision = previewDisplayDecisionForPath(path)
        // The record itself is the version source: it changes when an edit is saved, reset, or its
        // preview file is lost, and it stays equal across the decodes of one unchanged edit.
        val contentVersion = decision?.let(::resolvePhotoContentVersion)
            ?: PhotoContentVersion.Unspecified
        if (decision?.source == PreviewDisplaySource.UNAVAILABLE) {
            discardCachedPreview(path)
            return PhotoRenderState(
                thumbnail = null,
                full = null,
                isPreviewUnavailable = true,
                contentVersion = contentVersion
            )
        }
        return PhotoRenderState(
            thumbnail = thumbnailCache.get(path),
            full = fullBitmapCache.get(path),
            contentVersion = contentVersion
        )
    }

    private fun cachedThumbnailForPath(path: String): Bitmap? {
        if (previewDisplayDecisionForPath(path)?.source == PreviewDisplaySource.UNAVAILABLE) {
            discardCachedPreview(path)
            return null
        }
        return thumbnailCache.get(path)
    }

    private fun discardCachedPreview(path: String) {
        discardPreviewArtifacts(
            path = path,
            thumbnailDecodes = thumbnailDecodes,
            fullPreviewDecodes = fullPreviewDecodes,
            thumbnailCache = thumbnailCache,
            fullCache = fullBitmapCache
        )
    }

    private fun findPathByPhotoId(photoId: Long): String? {
        for (i in 0 until adapter.itemCount) {
            val item = adapter.getItem(i)
            if (item != null && item.id == photoId) return item.path
        }
        return null
    }

    private fun invalidateCachedBitmapsForPath(path: String) {
        // Revoking the decodes first makes every already running decode for this path stale: it
        // can neither publish its bitmap nor release the marker of the decode started below.
        thumbnailDecodes.invalidate(path)
        fullPreviewDecodes.invalidate(path)
        thumbnailCache.remove(path)
        fullBitmapCache.remove(path)
        originalPreviewCache.remove(path)
        jpegSourceInfoCache.remove(path)
        val idx = adapter.indexOfPath(path)
        if (idx >= 0) {
            ensurePhotoForPosition(path, idx)
        }
        adapter.notifyPathChanged(path)
        filmstripAdapter.notifyPathChanged(path)
        renderPhotoChrome()
    }

    private fun decodeSampledBitmap(path: String, targetLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val srcLongEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val scale = targetLongEdge.coerceAtLeast(1).toFloat() / srcLongEdge.toFloat()
        val reqWidth = (bounds.outWidth * scale).roundToInt().coerceAtLeast(1)
        val reqHeight = (bounds.outHeight * scale).roundToInt().coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (oom: OutOfMemoryError) {
            Timber.w(oom, "decodeSampledBitmap OOM for path=%s", path)
            null
        }
    }

    /**
     * Decodes the *original* pixels a press-and-hold compare shows.
     *
     * The pager normally displays the graded preview, so this deliberately bypasses
     * [snapshotPreviewDecodeSource] and reads the source file at the same preview resolution.
     * Blocking: called from an IO coroutine by [BeforeAfterCompareController].
     */
    private fun decodeOriginalPreview(path: String): Bitmap? {
        val ext = ExtensionUtils.fileExtension(path)
        return try {
            when {
                ExtensionUtils.isRawExtension(ext) -> RawWrapper.decodeThumbnailBitmap(path)
                ExtensionUtils.isJpegExtension(ext) -> {
                    val targetEdge = (previewLongEdgePx * FULL_PREVIEW_SCALE_FACTOR)
                        .coerceAtMost(FULL_PREVIEW_MAX_EDGE_PX)
                    decodeSampledBitmap(path, targetEdge)
                }
                else -> null
            }
        } catch (oom: OutOfMemoryError) {
            Timber.w(oom, "decodeOriginalPreview OOM for path=%s", path)
            null
        }
    }

    private fun exportCurrent() {
        if (adapter.itemCount == 0) {
            showInlineFeedback(getString(R.string.toast_no_exportable_photos))
            return
        }
        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        val hasRealEdit = editedGraphFor(index)?.let { !it.isIdentity() } == true
        requestExport(index, bakeEdited = hasRealEdit)
    }

    private fun showExportMenu() {
        if (adapter.itemCount == 0) {
            showInlineFeedback(getString(R.string.toast_no_exportable_photos))
            return
        }
        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        val hasRealEdit = editedGraphFor(index)?.let { !it.isIdentity() } == true
        val menu = PopupMenu(this, binding.exportButton)
        menu.menu.add(0, MENU_EXPORT_BAKED, 0, R.string.export_menu_edited).isEnabled = hasRealEdit
        menu.menu.add(0, MENU_EXPORT_ORIGINAL, 1, R.string.export_menu_original)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT_BAKED -> requestExport(index, bakeEdited = true)
                MENU_EXPORT_ORIGINAL -> requestExport(index, bakeEdited = false)
            }
            true
        }
        menu.show()
    }

    private fun requestExport(index: Int, bakeEdited: Boolean) {
        if (Build.VERSION.SDK_INT <= 28) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportIndex = index
                pendingExportBake = bakeEdited
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_WRITE_STORAGE
                )
                return
            }
        }
        exportIndex(index, bakeEdited)
    }

    private fun editedGraphFor(index: Int): EditGraph? {
        val entity = adapter.getItem(index) ?: return null
        val record = editedPhotoMap[entity.id] ?: return null
        return EditGraphJsonCodec.decode(record.graphJson)
    }

    private fun setExportProgressState(
        active: Boolean,
        showOverlay: Boolean = false,
        titleRes: Int = R.string.export_progress_baking_title,
        subtitle: String = ""
    ) {
        binding.exportButton.isEnabled = !active
        binding.exportButton.setImageDrawable(
            if (active) null else ContextCompat.getDrawable(this, R.drawable.ic_export_24)
        )
        binding.exportButtonSpinner.visibility = if (active) View.VISIBLE else View.GONE
        val overlay = binding.exportProgressOverlay
        overlay.animate().cancel()
        if (active && showOverlay) {
            binding.exportProgressTitle.setText(titleRes)
            binding.exportProgressSubtitle.text = subtitle
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(EXPORT_OVERLAY_FADE_MS).withEndAction(null).start()
        } else if (overlay.visibility == View.VISIBLE) {
            overlay.animate()
                .alpha(0f)
                .setDuration(EXPORT_OVERLAY_FADE_MS)
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        }
    }

    private fun bakeEditedPhoto(entity: PhotoEntity, graph: EditGraph) {
        val displayEntityName = entity.name ?: getString(R.string.photo_default_name)
        setExportProgressState(
            active = true,
            showOverlay = true,
            titleRes = R.string.export_progress_baking_title,
            subtitle = getString(R.string.export_progress_baking_detail_named, displayEntityName)
        )
        lifecycleScope.launch {
            try {
                val sink = EditorHostRegistry.gallerySink ?: gallerySink
                val result = BakeExporter(this@MainActivity, sink, filesDir).bake(
                    BakeRequest(
                        sourcePath = entity.path,
                        displayName = makeOutputName(entity.name, "_edited"),
                        isRaw = isRawPath(entity.path),
                        graph = graph
                    )
                )
                when (result) {
                    is BakeResult.Success -> {
                        result.uri?.let { uri ->
                            try {
                                dao.updateUriString(entity.id, uri.toString())
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to persist baked uriString for photo id=%d", entity.id)
                            }
                        }
                        showInlineFeedback(getString(R.string.toast_baked_saved, result.displayName))
                    }
                    is BakeResult.Failure -> {
                        Timber.w(result.cause, "Bake export failed: %s", result.reason)
                        showInlineFeedback(getString(R.string.toast_bake_failed))
                    }
                }
            } finally {
                setExportProgressState(active = false)
            }
        }
    }

    private fun exportIndex(index: Int, bakeEdited: Boolean = false) {
        val entity = adapter.getItem(index) ?: return
        if (bakeEdited) {
            bakeEditedPhoto(entity, editedGraphFor(index) ?: EditGraph())
            return
        }
        val displayEntityName = entity.name ?: getString(R.string.photo_default_name)
        val isRaw = isRawPath(entity.path)
        setExportProgressState(
            active = true,
            showOverlay = isRaw,
            titleRes = R.string.export_progress_original_title,
            subtitle = displayEntityName
        )
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                exportOriginalInBackground(entity)
            } finally {
                withContext(Dispatchers.Main) {
                    setExportProgressState(active = false)
                }
            }
        }
    }

    private suspend fun exportOriginalInBackground(entity: PhotoEntity) {
        val path = entity.path
        if (path.isEmpty()) {
            withContext(Dispatchers.Main) {
                showInlineFeedback(getString(R.string.toast_export_failed_invalid_path))
            }
            return
        }
        val ext = ExtensionUtils.fileExtension(path)
        val displayName = makeOutputName(entity.name, "_export")
        val saved = exportOriginalUri(path, ext, displayName)
        if (saved != null) {
            try {
                dao.updateUriString(entity.id, saved.toString())
            } catch (e: Exception) {
                Timber.w(e, "Failed to persist exported uriString for photo id=%d", entity.id)
            }
        }
        withContext(Dispatchers.Main) {
            val msg = if (saved != null) {
                getString(R.string.toast_saved_to_gallery, displayName)
            } else {
                getString(R.string.toast_save_failed)
            }
            showInlineFeedback(msg)
        }
    }

    private suspend fun exportOriginalUri(path: String, ext: String, displayName: String): Uri? = try {
        when {
            ExtensionUtils.isJpegExtension(ext) -> saveFileToGalleryByCopying(File(path), displayName)
            ExtensionUtils.isRawExtension(ext) -> exportOriginalRawUri(path, displayName)
            else -> null
        }
    } catch (e: Exception) {
        Timber.e(e, "exportOriginalUri: save failed")
        null
    }

    private suspend fun exportOriginalRawUri(path: String, displayName: String): Uri? {
        val cachedBmp = fullBitmapCache.get(path)
        val bmp = cachedBmp ?: withContext(rawExportDispatcher) {
            try {
                RawWrapper.decodeToBitmap(path)
            } catch (e: Exception) {
                Timber.w(e, "RAW decode failed for path=%s, falling back to null", path)
                null
            }
        }
        return try {
            bmp?.let { saveBitmapToGallery(it, displayName, rawSourcePath = path) }
        } finally {
            if (cachedBmp == null) bmp?.recycle()
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val idx = pendingExportIndex
            val bakeEdited = pendingExportBake
            pendingExportIndex = null
            pendingExportBake = false
            if (granted && idx != null) {
                exportIndex(idx, bakeEdited)
            } else if (!granted) {
                showInlineFeedback(getString(R.string.toast_storage_permission_denied))
            }
        }
    }

    private fun makeOutputName(name: String?, suffix: String): String {
        return if (!name.isNullOrBlank()) {
            name.substringBeforeLast('.') + "$suffix.jpg"
        } else {
            val timestamp = exportDateFormat.get()?.format(Date()) ?: System.currentTimeMillis()
            val tail = if (suffix == "_edited") "_edited" else ""
            "IMG_$timestamp$tail.jpg"
        }
    }

    private fun saveBitmapToGallery(
        bitmap: Bitmap,
        displayName: String,
        rawSourcePath: String? = null
    ): Uri? {
        val rawMetadata = rawSourcePath?.let { src ->
            try {
                RawWrapper.extractExifMetadata(src)
            } catch (e: Exception) {
                Timber.w(e, "Failed to extract RAW EXIF metadata")
                null
            }
        }
        val outWidth = bitmap.width
        val outHeight = bitmap.height
        return gallerySink.save(
            displayName = displayName,
            mime = JPEG_MIME,
            write = { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_EXPORT_QUALITY, out)) {
                    throw IOException("JPEG compress failed")
                }
            },
            writeExif = if (rawMetadata != null) {
                { exifWriter ->
                    ExifCopier.applyFromRawMetadata(
                        metadata = rawMetadata,
                        target = exifWriter,
                        outputWidth = outWidth,
                        outputHeight = outHeight
                    )
                }
            } else {
                null
            }
        )
    }

    // 直接复制文件至相册（用于JPEG等已经是目标格式的文件）
    private fun saveFileToGalleryByCopying(
        srcFile: File,
        displayName: String,
        mime: String = JPEG_MIME
    ): Uri? {
        return gallerySink.save(
            displayName = displayName,
            mime = mime,
            write = { out ->
                srcFile.inputStream().use { input -> input.copyTo(out) }
            }
        )
    }

    /**
     * Shows confirmation dialog before deleting current photo.
     *
     * The camera's copy is only offered when it can actually be deleted: a session is
     * open, the camera implements DeleteObject, and this photo recorded a handle when it
     * was imported.
     */
    private fun showDeleteConfirmDialog() {
        if (adapter.itemCount == 0) {
            showInlineFeedback(getString(R.string.toast_no_deletable_photos))
            return
        }

        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        val entity = adapter.getItem(index) ?: return

        val blocker = cameraDeleteBlockedBy(
            connected = connectionState is UsbPtpConnectionState.Connected,
            cameraSupportsDelete = connectionController.canDeleteOnCamera(),
            hasObjectHandle = entity.objectHandle != null
        )
        val displayName = entity.name ?: getString(R.string.photo_default_name)
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_photo, null)
        val cameraDeleteBox = dialogView.findViewById<View>(R.id.cameraDeleteBox)
        val deleteOnCameraCheck = dialogView.findViewById<CheckBox>(R.id.deleteOnCameraCheck)
        val blockerHint = dialogView.findViewById<TextView>(R.id.cameraDeleteHint)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val canDeleteOnCamera = blocker == null
        val rememberedDeleteOnCamera = prefs.getBoolean(PREF_DELETE_ON_CAMERA, false)
        deleteOnCameraCheck.isChecked = initialDeleteOnCameraChecked(
            canDeleteOnCamera = canDeleteOnCamera,
            rememberedDeleteOnCamera = rememberedDeleteOnCamera
        )
        deleteOnCameraCheck.isEnabled = canDeleteOnCamera
        if (canDeleteOnCamera) {
            cameraDeleteBox.setBackgroundResource(R.drawable.bg_camera_delete_box)
            deleteOnCameraCheck.setTextColor(ContextCompat.getColor(this, R.color.danger_text))
            blockerHint.visibility = View.GONE
        } else {
            cameraDeleteBox.setBackgroundResource(R.drawable.bg_camera_delete_box_disabled)
            deleteOnCameraCheck.setTextColor(
                ContextCompat.getColor(this, R.color.darkroom_text_muted)
            )
            blockerHint.setText(cameraDeleteBlockerMessage(blocker))
            blockerHint.visibility = View.VISIBLE
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title_named, displayName))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ ->
                if (canDeleteOnCamera) {
                    prefs.edit()
                        .putBoolean(PREF_DELETE_ON_CAMERA, deleteOnCameraCheck.isChecked)
                        .apply()
                }
                deleteCurrent(index, deleteOnCameraCheck.isChecked)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    /**
     * Deletes the photo at the given index.
     *
     * When [deleteOnCamera] is set the camera's copy goes first: if the camera refuses,
     * nothing is deleted locally and the reason is shown, so the user can retry instead of
     * losing the only copy they can still see.
     *
     * @param index The index of the photo to delete.
     * @param deleteOnCamera Whether the camera's original should be deleted too.
     */
    private fun deleteCurrent(index: Int, deleteOnCamera: Boolean) {
        val entity = adapter.getItem(index) ?: return
        val displayName = entity.name ?: getString(R.string.photo_default_name)

        showInlineFeedback(getString(R.string.toast_deleting, displayName))

        lifecycleScope.launch {
            if (deleteOnCamera) {
                val objectHandle = entity.objectHandle
                if (objectHandle == null) {
                    showInlineFeedback(getString(R.string.camera_delete_failed_not_found))
                    return@launch
                }
                val result = connectionController.deleteOnCamera(objectHandle)
                val failureMessage = cameraDeleteFailureMessage(result)
                if (failureMessage != null) {
                    Timber.w("deleteOnCamera refused: %s", result)
                    showInlineFeedback(getString(failureMessage))
                    return@launch
                }
            }
            deleteLocalCopy(entity, displayName)
        }
    }

    /**
     * Removes one photo from the cache, the filesystem and the library.
     */
    private suspend fun deleteLocalCopy(entity: PhotoEntity, displayName: String) {
        withContext(Dispatchers.IO) {
            try {
                // Remove from cache
                thumbnailDecodes.invalidate(entity.path)
                fullPreviewDecodes.invalidate(entity.path)
                thumbnailCache.remove(entity.path)
                fullBitmapCache.remove(entity.path)
                jpegSourceInfoCache.remove(entity.path)

                // Delete physical file and any rendered editor preview caches
                val file = File(entity.path)
                val fileDeleted = if (file.exists()) file.delete() else true
                clearPhotoEditCacheDir(cacheDir, entity.id)

                // Remove from database
                dao.delete(entity)

                withContext(Dispatchers.Main) {
                    if (fileDeleted) {
                        showInlineFeedback(getString(R.string.toast_deleted, displayName))
                    } else {
                        showInlineFeedback(getString(R.string.toast_delete_failed))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "deleteLocalCopy failed")
                withContext(Dispatchers.Main) {
                    showInlineFeedback(getString(R.string.toast_delete_failed))
                }
            }
        }
    }

}

@VisibleForTesting
internal fun shouldLoadThumbnail(isCurrentPage: Boolean, isRaw: Boolean): Boolean {
    return isCurrentPage || !isRaw
}

@VisibleForTesting
internal fun calculateInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    if (srcWidth <= 0 || srcHeight <= 0) return 1
    if (reqWidth <= 0 || reqHeight <= 0) return 1

    var inSampleSize = 1
    if (srcHeight > reqHeight || srcWidth > reqWidth) {
        val halfHeight = srcHeight / 2
        val halfWidth = srcWidth / 2

        while (halfHeight / inSampleSize >= reqHeight &&
            halfWidth / inSampleSize >= reqWidth
        ) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
