package cn.devcxl.photosync.activity

import kotlin.math.roundToInt

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

import android.Manifest
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.adapter.PhotoRenderState
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import cn.devcxl.photosync.ptp.manager.cameraDeleteBlockedBy
import cn.devcxl.photosync.utils.ExtensionUtils
import cn.devcxl.photosync.wrapper.RawWrapper

class MainActivity : AppCompatActivity() {

    private val connectionController by lazy {
        (application as App).usbPtpConnectionController
    }

    companion object {
        private const val REQ_WRITE_STORAGE = 2001
        private const val THUMBNAIL_MAX_EDGE_PX = 512
        private const val RAW_PREVIEW_MAX_EDGE_PX = 1200
        private const val FULL_PREVIEW_SCALE_FACTOR = 2
        private const val FULL_PREVIEW_MAX_EDGE_PX = 4096
        private const val JPEG_MIME = "image/jpeg"
        private const val JPEG_EXPORT_QUALITY = 95
        private const val GALLERY_DIR_NAME = "PhotoSync"
        private const val PREFS_NAME = "photosync_prefs"
        private const val PREF_DELETE_ON_CAMERA = "delete_on_camera"
        private val exportDateFormat = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
    }

    private lateinit var adapter: PhotoPagerAdapter
    private lateinit var filmstripAdapter: FilmstripAdapter
    private var pendingExportIndex: Int? = null

    private lateinit var binding: ActivityMainBinding

    private lateinit var dao: PhotoDao
    private var pendingRevealPath: String? = null

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideFeedbackRunnable = Runnable { hideInlineFeedback() }

    private val thumbnailCache: LruCache<String, Bitmap> by lazy { createBitmapCache(16) }
    private val fullBitmapCache: LruCache<String, Bitmap> by lazy { createBitmapCache(8) }
    private val inFlightThumbnailPaths = Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlightFullPaths = Collections.synchronizedSet(mutableSetOf<String>())
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
        observeDatabase()
        observeConnectionState()
        setupButtons()
        configureConnectionController()
        applySystemBarVisibility(showOverlays = true)
        connectionController.refreshAvailableCameras()
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
            renderStateProvider = { path ->
                PhotoRenderState(
                    thumbnail = thumbnailCache.get(path),
                    full = fullBitmapCache.get(path)
                )
            },
            onBindRequest = { path, position ->
                ensurePhotoForPosition(path, position)
            },
            jpegSourceInfoProvider = { path -> getJpegSourceInfo(path) },
            isCurrentPageProvider = { position -> position == binding.viewPager.currentItem },
            isJpegProvider = { path -> isJpegPath(path) },
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
            }
        })
    }

    private fun setupFilmstrip() {
        filmstripAdapter = FilmstripAdapter(
            thumbnailProvider = { path -> thumbnailCache.get(path) },
            onThumbnailRequest = { path -> ensureThumbnail(path) },
            onItemClick = { position ->
                if (position in 0 until adapter.itemCount) {
                    binding.viewPager.setCurrentItem(position, true)
                }
            }
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
                    pendingRevealPath?.let { target ->
                        val idx = list.indexOfFirst { it.path == target }
                        if (idx >= 0) {
                            val wasLatest = isViewingLatestPhoto(previousIndex, previousCount)
                            if (shouldAutoAdvanceOnNewPhoto(autoFollowEnabled, currentPageZoomed, wasLatest)) {
                                binding.viewPager.setCurrentItem(idx, previousCount > 0)
                                pendingNewPhotoCount = 0
                            } else {
                                pendingNewPhotoCount++
                            }
                            pendingRevealPath = null
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
        binding.photoNameText.text = item.name ?: getString(R.string.photo_default_name)

        val exported = isPhotoExported(item.uriString)
        binding.photoExportedBadge.visibility = if (exported) View.VISIBLE else View.GONE
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

        when (resolvePhotoFormatBadge(item.path)) {
            PhotoFormatBadge.RAW -> {
                binding.photoFormatBadge.visibility = View.VISIBLE
                binding.photoFormatBadge.setText(R.string.format_raw)
                binding.photoFormatBadge.setBackgroundResource(R.drawable.bg_badge_raw)
                binding.photoFormatBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.badge_raw_text)
                )
            }
            PhotoFormatBadge.JPEG -> {
                binding.photoFormatBadge.visibility = View.VISIBLE
                binding.photoFormatBadge.setText(R.string.format_jpeg)
                binding.photoFormatBadge.setBackgroundResource(R.drawable.bg_badge_jpg)
                binding.photoFormatBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.badge_jpg_text)
                )
            }
            PhotoFormatBadge.OTHER -> {
                binding.photoFormatBadge.visibility = View.GONE
            }
        }
        updateInfoToggleAppearance()
        renderInfoDrawer()
    }

    private fun updateInfoToggleAppearance() {
        binding.infoToggleButton.setBackgroundResource(
            if (infoDrawerOpen) R.drawable.bg_toggle_pill_on else R.drawable.bg_toggle_pill
        )
        val colorRes = if (infoDrawerOpen) {
            R.color.darkroom_text_primary
        } else {
            R.color.darkroom_text_secondary
        }
        binding.infoToggleLabel.setTextColor(ContextCompat.getColor(this, colorRes))
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
        binding.exportButton.setOnClickListener { exportCurrent() }
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
        connectionController.setFileDownloadedListener(null)
        // The session belongs to the process, not to this activity (docs/adr/0001):
        // a system-driven recreate must not tear down a healthy session, so only a real
        // exit releases the camera.
        if (isFinishing && !isChangingConfigurations) {
            connectionController.disconnect("activity_finished")
        }
        super.onDestroy()
    }

    private fun configureConnectionController() {
        connectionController.setFileDownloadPath(externalCacheDir?.absolutePath)
        connectionController.setFileTransferListener { _, totalByteLength, transterByteLength ->
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
        connectionController.setFileDownloadedListener { fileHandle, localFile, timeduring ->
            Timber.v("file downloaded at %s, time: %sms", localFile.absolutePath, timeduring)
            lifecycleScope.launch(Dispatchers.Main) {
                binding.transferStatusCard.visibility = View.GONE
            }

            val ext = localFile.extension.lowercase(Locale.ROOT)
            val isRaw = ExtensionUtils.isRawExtension(ext)
            val isJpeg = ExtensionUtils.isJpegExtension(ext)
            Timber.d("file downloaded: %s ext=%s raw=%b jpeg=%b",
                localFile.name, ext, isRaw, isJpeg)
            if (isRaw || isJpeg) {
                insertItemAndReveal(localFile, fileHandle)
            }
        }
    }

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

    private fun insertItemAndReveal(localFile: File, objectHandle: Int) {
        val entity = PhotoEntity(
            path = localFile.absolutePath,
            name = localFile.name,
            uriString = null,
            objectHandle = objectHandle
        )
        pendingRevealPath = entity.path
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                dao.insert(entity)
            } catch (e: Exception) {
                Timber.e(e, "DB insert failed")
            }
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
        jpegSourceInfoCache.keys.retainAll(livePaths)
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

    private fun ensureThumbnail(path: String) {
        if (thumbnailCache.get(path) != null || fullBitmapCache.get(path) != null) return
        if (!inFlightThumbnailPaths.add(path)) return
        val isRaw = isRawPath(path)
        val dispatcher = if (isRaw) rawThumbnailDispatcher else Dispatchers.IO
        lifecycleScope.launch(dispatcher) {
            try {
                if (isRaw) {
                    Timber.d("start RAW thumbnail decode: %s", path)
                }
                val bitmap = decodeThumbnailBitmap(path) ?: return@launch
                if (isRaw) {
                    Timber.d("decoded RAW thumbnail bitmap %dx%d: %s", bitmap.width, bitmap.height, path)
                }
                withContext(Dispatchers.Main) {
                    thumbnailCache.put(path, bitmap)
                    adapter.notifyPathChanged(path)
                    filmstripAdapter.notifyPathChanged(path)
                    if (infoDrawerOpen && infoTargetPath == path) {
                        renderInfoDrawer()
                    }
                }
            } finally {
                inFlightThumbnailPaths.remove(path)
            }
        }
    }

    private fun ensureFullPreview(path: String) {
        if (fullBitmapCache.get(path) != null) return
        if (isRawPath(path)) return
        if (!inFlightFullPaths.add(path)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeFullPreviewBitmap(path) ?: return@launch
                withContext(Dispatchers.Main) {
                    fullBitmapCache.put(path, bitmap)
                    adapter.notifyPathChanged(path)
                }
            } finally {
                inFlightFullPaths.remove(path)
            }
        }
    }

    private fun decodeThumbnailBitmap(path: String): Bitmap? {
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

    private fun decodeFullPreviewBitmap(path: String): Bitmap? {
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

    private fun exportCurrent() {
        if (adapter.itemCount == 0) {
            showInlineFeedback(getString(R.string.toast_no_exportable_photos))
            return
        }
        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        if (Build.VERSION.SDK_INT <= 28) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportIndex = index
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_WRITE_STORAGE
                )
                return
            }
        }
        exportIndex(index)
    }

    private fun exportIndex(index: Int) {
        val entity = adapter.getItem(index) ?: return
        val displayEntityName = entity.name ?: getString(R.string.photo_default_name)
        showInlineFeedback(getString(R.string.toast_exporting, displayEntityName))

        lifecycleScope.launch(Dispatchers.IO) {
            val path = entity.path
            if (path.isEmpty()) {
                withContext(Dispatchers.Main) {
                    showInlineFeedback(getString(R.string.toast_export_failed_invalid_path))
                }
                return@launch
            }
            val ext = ExtensionUtils.fileExtension(path)

            val displayName = makeExportName(entity.name)
            val saved: Uri? = try {
                if (ExtensionUtils.isJpegExtension(ext)) {
                    // JPEG：直接复制原文件到相册，避免解码与重压缩
                    saveFileToGalleryByCopying(File(path), displayName)
                } else if (ExtensionUtils.isRawExtension(ext)) {
                    // RAW：优先使用缓存Bitmap，否则直接原生解码为 ARGB_8888 Bitmap
                    val cachedBmp = fullBitmapCache.get(path)
                    val bmp = cachedBmp ?: withContext(rawExportDispatcher) {
                        try {
                            RawWrapper.decodeToBitmap(path)
                        } catch (e: Exception) {
                            Timber.w(e, "RAW decode failed for path=%s, falling back to null", path)
                            null
                        }
                    }
                    try {
                        if (bmp != null) saveBitmapToGallery(bmp, displayName) else null
                    } finally {
                        if (cachedBmp == null) {
                            bmp?.recycle()
                        }
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "exportIndex: save failed")
                null
            }

            if (saved != null) {
                try {
                    dao.updateUriString(entity.id, saved.toString())
                } catch (e: Exception) {
                    Timber.w(e, "Failed to persist exported uriString for photo id=%d", entity.id)
                }
            }

            withContext(Dispatchers.Main) {
                if (saved != null) {
                    showInlineFeedback(getString(R.string.toast_saved_to_gallery, displayName))
                } else {
                    showInlineFeedback(getString(R.string.toast_save_failed))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val idx = pendingExportIndex
            pendingExportIndex = null
            if (granted && idx != null) {
                exportIndex(idx)
            } else if (!granted) {
                showInlineFeedback(getString(R.string.toast_storage_permission_denied))
            }
        }
    }

    private fun makeExportName(name: String?): String {
        return if (!name.isNullOrBlank()) {
            name.substringBeforeLast('.') + "_export.jpg"
        } else {
            val ts = exportDateFormat.get()!!.format(Date())
            "IMG_${ts}.jpg"
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): Uri? {
        return saveToGallery(displayName, mime = JPEG_MIME) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_EXPORT_QUALITY, out)) {
                throw IOException("JPEG compress failed")
            }
        }
    }

    // 直接复制文件至相册（用于JPEG等已经是目标格式的文件）
    private fun saveFileToGalleryByCopying(srcFile: File, displayName: String, mime: String = JPEG_MIME): Uri? {
        return saveToGallery(displayName, mime) { out ->
            srcFile.inputStream().use { input -> input.copyTo(out) }
        }
    }

    /**
     * Writes one image into the system gallery under Pictures/PhotoSync.
     *
     * On API 29+ the entry is inserted through MediaStore with `IS_PENDING` set, so a
     * failed write never leaves a half-written image visible. Older versions write the
     * file directly to external storage, then announce it to MediaStore and return that
     * insert's result.
     *
     * @param displayName File name to publish in the gallery.
     * @param mime MIME type to record in MediaStore.
     * @param write Callback that writes the image bytes; returning normally means success,
     *   throwing marks the entry as failed and it is removed again.
     * @return The MediaStore [Uri] of the new entry, or null when saving failed.
     */
    private fun saveToGallery(displayName: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoSync")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try {
                        val stream = resolver.openOutputStream(uri)
                            ?: throw IOException("openOutputStream returned null")
                        stream.use { out ->
                            write(out)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } else {
                val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val dir = File(pictures, GALLERY_DIR_NAME).apply { if (!exists()) mkdirs() }
                val file = File(dir, displayName)
                FileOutputStream(file).use { out ->
                    write(out)
                    out.flush()
                }
                // Insert into MediaStore on older devices so it shows up in gallery
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Timber.e(e, "saveToGallery failed for %s", displayName)
            null
        }
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
                thumbnailCache.remove(entity.path)
                fullBitmapCache.remove(entity.path)
                inFlightThumbnailPaths.remove(entity.path)
                inFlightFullPaths.remove(entity.path)
                jpegSourceInfoCache.remove(entity.path)

                // Delete physical file
                val file = File(entity.path)
                val fileDeleted = if (file.exists()) file.delete() else true

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
