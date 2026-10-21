package com.markreader.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Spanned
import com.markreader.ui.reader.SearchMatches
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.markreader.data.AppThemeModePreference
import com.markreader.data.PreferencesRepository
import com.markreader.data.RecentFilesRepository
import com.markreader.data.UserPreferences
import com.markreader.file.DocumentType
import com.markreader.file.DocumentTypeResolver
import com.markreader.format.DocumentFormat
import com.markreader.format.FormatCapabilities
import com.markreader.format.FormatRegistry
import com.markreader.format.HeadingItem
import com.markreader.format.ReaderContent
import com.markreader.format.RenderTheme
import com.markreader.ui.UiMessage
import com.markreader.ui.reader.SearchHighlightSpan
import com.markreader.R
import com.markreader.file.DocumentSource
import com.markreader.file.FileTooLargeException
import com.markreader.file.MAX_FILE_BYTES
import com.markreader.file.TextCodec
import com.markreader.file.tryTakePersistablePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewMode {
    Rendered,
    Raw
}

data class ViewerUiState(
    val isLoading: Boolean = true,
    val isLoadingVisible: Boolean = false,
    val fileName: String = "",
    val rawText: String = "",
    val rawHighlighted: Spanned? = null,
    val rendered: Spanned? = null,
    val errorMessage: UiMessage? = null,
    val warningMessage: UiMessage? = null,
    val isEmptyFile: Boolean = false,
    val needsPermission: Boolean = false,
    val viewMode: ViewMode = ViewMode.Rendered,
    val headings: List<HeadingItem> = emptyList(),
    val activeHeadingIndex: Int = -1,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMatchCount: Int = 0,
    val searchMatchIndex: Int = 0,
    /**
     * Match positions only. Not part of the rendered text: copying a large document to attach a
     * span per match is what made searching one stall. Null while no query is active.
     */
    val searchMatches: SearchMatches? = null,
    val userPreferences: UserPreferences = UserPreferences(),
    val documentType: DocumentType = DocumentType.PlainText,
    /** What the chrome may offer, decided by the document's format rather than by its type. */
    val capabilities: FormatCapabilities = FormatCapabilities(),
    /** Shown in place of a view mode where [capabilities] says there is no raw view. */
    @StringRes val formatNameRes: Int = R.string.viewer_subtitle_plain_text
)

class ViewerViewModel(
    application: Application,
    private val initialUri: String?
) : AndroidViewModel(application) {
    private data class RenderCacheKey(
        val documentType: DocumentType,
        val theme: RenderTheme
    )

    private val repository = PreferencesRepository.getInstance(application)
    private val recentFilesRepository = RecentFilesRepository.getInstance(application)
    private var systemDarkTheme = false
    private val formats = FormatRegistry(application)
    /** The format for the document on screen, held so its renderers survive a theme toggle. */
    private var currentFormat: DocumentFormat? = null
    private var currentUri: String? = null
    private var loadJob: Job? = null
    private var loadingJob: Job? = null
    private var rebuildJob: Job? = null
    private var searchJob: Job? = null
    private var baseRendered: Spanned? = null
    private var renderDarkModeOverride: Boolean? = null
    private var preferencesLoaded = false
    private var pendingExternalRerender = initialUri != null
    private var matches: SearchMatches = SearchMatches.find("", "")
    private var renderCacheTextHash = 0
    private val renderCache = mutableMapOf<RenderCacheKey, Spanned>()

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _scrollToOffset = MutableStateFlow<Int?>(null)
    val scrollToOffset: StateFlow<Int?> = _scrollToOffset.asStateFlow()

    private val _scrollY = MutableStateFlow(0)
    val scrollY: StateFlow<Int> = _scrollY.asStateFlow()

    // Fraction of the document scrolled past, or null while the content is
    // shorter than the viewport (no meaningful progress to show).
    private val _scrollProgress = MutableStateFlow<Float?>(null)
    val scrollProgress: StateFlow<Float?> = _scrollProgress.asStateFlow()

    init {
        observePreferences()
        if (initialUri == null) {
            _uiState.value = ViewerUiState(
                isLoading = false,
                errorMessage = UiMessage(R.string.viewer_error_no_file_selected)
            )
        }
    }

    fun loadUri(uriString: String, forceReload: Boolean = false) {
        if (!forceReload && currentUri == uriString && _uiState.value.rawText.isNotEmpty()) {
            return
        }
        currentUri = uriString
        // Cancel any in-flight load: a slow read for a previous URI must not
        // finish after this one and overwrite the newer content.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadingJob?.cancel()
            searchJob?.cancel()
            matches = SearchMatches.find("", "")
            _scrollProgress.value = null
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isLoadingVisible = false,
                errorMessage = null,
                warningMessage = null,
                isEmptyFile = false,
                needsPermission = false,
                rawText = "",
                rawHighlighted = null,
                rendered = null,
                headings = emptyList(),
                activeHeadingIndex = -1,
                isSearchActive = false,
                searchQuery = "",
                searchMatchCount = 0,
                searchMatchIndex = 0,
                searchMatches = null
            )

            loadingJob = viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                if (_uiState.value.isLoading) {
                    _uiState.value = _uiState.value.copy(isLoadingVisible = true)
                }
            }

            val uri = Uri.parse(uriString)

            ensureReadPermission(uri)

            val fileName = resolveFileName(uri) ?: getApplication<Application>().getString(R.string.untitled)
            val mimeType = resolveMimeType(uri)

            var readError: Exception? = null
            val markdown = try {
                readTextFromUri(uri)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                readError = ex
                null
            }

            if (markdown == null) {
                loadingJob?.cancel()
                val needsPermission = readError is SecurityException
                val errorMessage = if (readError is FileTooLargeException) {
                    UiMessage(
                        R.string.viewer_error_file_too_large,
                        listOf(MAX_FILE_BYTES / (1024 * 1024))
                    )
                } else {
                    describeReadFailure(readError)
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingVisible = false,
                    fileName = fileName,
                    errorMessage = errorMessage,
                    needsPermission = needsPermission,
                    rawHighlighted = null,
                    searchQuery = "",
                    searchMatchCount = 0,
                    searchMatchIndex = 0,
                searchMatches = null
                )
                return@launch
            }

            recentFilesRepository.recordOpen(uriString, fileName)

            val textHash = markdown.hashCode()
            if (renderCacheTextHash != textHash) {
                renderCacheTextHash = textHash
                renderCache.clear()
            }

            if (markdown.isBlank()) {
                loadingJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingVisible = false,
                    fileName = fileName,
                    rawText = markdown,
                    rawHighlighted = null,
                    isEmptyFile = true,
                    searchQuery = "",
                    searchMatchCount = 0,
                    searchMatchIndex = 0,
                searchMatches = null
                )
                return@launch
            }

            val documentType = DocumentTypeResolver.resolve(fileName, mimeType, markdown)
            val format = formats.formatFor(documentType)
            currentFormat = format
            val theme = RenderTheme(
                renderDarkModeOverride ?: isDarkTheme(_uiState.value.userPreferences, systemDarkTheme)
            )
            val loaded = format?.load(markdown, theme).asSpans()

            if (format == null || loaded == null) {
                loadingJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingVisible = false,
                    fileName = fileName,
                    rawText = markdown,
                    rawHighlighted = null,
                    viewMode = ViewMode.Raw,
                    searchQuery = "",
                    searchMatchCount = 0,
                    searchMatchIndex = 0,
                searchMatches = null,
                    documentType = documentType,
                    // A null format is DocumentType.Binary: bytes that are not text, so there
                    // is no format to ask and nothing but the raw view to offer.
                    capabilities = format?.capabilities ?: FormatCapabilities(),
                    formatNameRes = format?.nameRes ?: R.string.viewer_subtitle_raw_mode,
                    warningMessage = UiMessage(
                        format?.renderFailureWarningRes ?: R.string.viewer_warning_binary
                    )
                )
                baseRendered = null
                return@launch
            }

            val headings = format.headings(markdown, loaded)
            loadingJob?.cancel()
            baseRendered = loaded.spanned
            renderCache[RenderCacheKey(documentType, theme)] = loaded.spanned
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isLoadingVisible = false,
                fileName = fileName,
                rawText = markdown,
                rawHighlighted = null,
                rendered = loaded.spanned,
                errorMessage = null,
                warningMessage = null,
                headings = headings,
                activeHeadingIndex = clampActiveHeadingIndex(_uiState.value.activeHeadingIndex, headings),
                documentType = documentType,
                capabilities = format.capabilities,
                formatNameRes = format.nameRes
            )

            applySearchQuery(_uiState.value.searchQuery)
            if (pendingExternalRerender && preferencesLoaded) {
                pendingExternalRerender = false
                scheduleRebuild()
            }
        }
    }

    fun toggleViewMode() {
        val next = if (_uiState.value.viewMode == ViewMode.Rendered) {
            ViewMode.Raw
        } else {
            ViewMode.Rendered
        }
        _uiState.value = _uiState.value.copy(viewMode = next)
        applySearchQuery(_uiState.value.searchQuery)
    }

    fun onSearchToggled() {
        val active = !_uiState.value.isSearchActive
        _uiState.value = _uiState.value.copy(
            isSearchActive = active,
            searchQuery = if (active) _uiState.value.searchQuery else "",
            searchMatchCount = 0,
            searchMatchIndex = 0,
                searchMatches = null
        )
        if (!active) {
            searchJob?.cancel()
            applySearchQuery("")
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            searchMatchIndex = 0,
                searchMatches = null
        )
        searchJob?.cancel()
        if (query.isBlank()) {
            applySearchQuery(query)
            return
        }
        // Debounced: the highlight pass rebuilds a span per match over the whole
        // document, which is too heavy to run per keystroke on large files.
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            applySearchQuery(query)
            // Bring the first match into view; without this, a query whose
            // matches are all off-screen gives no visible feedback.
            if (matches.count > 0) _scrollToOffset.value = matches.startAt(0)
        }
    }

    fun onNextMatch() {
        val count = _uiState.value.searchMatchCount
        if (count == 0) return
        val next = (_uiState.value.searchMatchIndex + 1) % count
        _uiState.value = _uiState.value.copy(searchMatchIndex = next)
        _scrollToOffset.value = matches.startAt(next)
    }

    fun onPreviousMatch() {
        val count = _uiState.value.searchMatchCount
        if (count == 0) return
        val prev = if (_uiState.value.searchMatchIndex - 1 < 0) count - 1 else _uiState.value.searchMatchIndex - 1
        _uiState.value = _uiState.value.copy(searchMatchIndex = prev)
        _scrollToOffset.value = matches.startAt(prev)
    }

    fun onHeadingSelected(offset: Int) {
        _scrollToOffset.value = offset
    }

    fun onScrollPositionChanged(y: Int, maxY: Int) {
        _scrollY.value = y
        _scrollProgress.value = progressFor(y, maxY)
    }

    /**
     * The scrollable extent changed without the position moving — a relayout
     * rather than a scroll. Updates progress only: publishing a position here
     * would make layout passes indistinguishable from user scrolling.
     */
    fun onScrollExtentChanged(maxY: Int) {
        _scrollProgress.value = progressFor(_scrollY.value, maxY)
    }

    private fun progressFor(y: Int, maxY: Int): Float? = if (maxY > 0) {
        (y.toFloat() / maxY).coerceIn(0f, 1f)
    } else {
        null
    }

    fun onScrollConsumed() {
        _scrollToOffset.value = null
    }

    fun onActiveHeadingChanged(index: Int) {
        val clamped = clampActiveHeadingIndex(index, _uiState.value.headings)
        if (_uiState.value.activeHeadingIndex != clamped) {
            _uiState.value = _uiState.value.copy(activeHeadingIndex = clamped)
        }
    }

    fun onReadingSurfaceModeChanged(isDarkSurface: Boolean) {
        if (renderDarkModeOverride == isDarkSurface) return
        renderDarkModeOverride = isDarkSurface
        scheduleRebuild()
    }

    fun onSystemDarkThemeChanged(isDark: Boolean) {
        if (systemDarkTheme == isDark) return
        systemDarkTheme = isDark
        if (renderDarkModeOverride == null) {
            scheduleRebuild()
        }
    }

    fun onPermissionRequestConsumed() {
        _uiState.value = _uiState.value.copy(needsPermission = false)
    }

    private fun observePreferences() {
        viewModelScope.launch {
            repository.preferences.collect { prefs ->
                if (!preferencesLoaded) {
                    preferencesLoaded = true
                }
                val previous = _uiState.value.userPreferences
                val themeChanged = prefs.appThemeMode != previous.appThemeMode ||
                    prefs.readerLightTheme != previous.readerLightTheme ||
                    prefs.readerDarkTheme != previous.readerDarkTheme
                val requiresRebuild = themeChanged ||
                    prefs.readingFont != previous.readingFont ||
                    prefs.codeFont != previous.codeFont ||
                    prefs.fontSizeSp != previous.fontSizeSp ||
                    prefs.lineHeight != previous.lineHeight

                if (themeChanged) {
                    formats.invalidateRenderers()
                }

                _uiState.value = _uiState.value.copy(userPreferences = prefs)

                if (pendingExternalRerender && _uiState.value.rawText.isNotBlank()) {
                    pendingExternalRerender = false
                    scheduleRebuild()
                }

                if (requiresRebuild) {
                    scheduleRebuild()
                }
            }
        }
    }

    private fun rebuildRenderedForTheme() {
        val markdown = _uiState.value.rawText
        if (markdown.isBlank()) return
        val format = currentFormat ?: return
        val theme = currentTheme()
        val documentType = _uiState.value.documentType
        viewModelScope.launch {
            if (tryApplyCachedRender(documentType, theme)) {
                return@launch
            }
            val loaded = format.load(markdown, theme).asSpans() ?: return@launch
            baseRendered = loaded.spanned
            renderCache[RenderCacheKey(documentType, theme)] = loaded.spanned
            _uiState.value = _uiState.value.copy(rendered = loaded.spanned)
            applySearchQuery(_uiState.value.searchQuery)
        }
    }

    private fun scheduleRebuild() {
        rebuildJob?.cancel()
        if (tryApplyCachedRender(_uiState.value.documentType, currentTheme())) {
            return
        }
        rebuildJob = viewModelScope.launch {
            kotlinx.coroutines.delay(REBUILD_DEBOUNCE_MS)
            rebuildRenderedForTheme()
        }
    }

    private fun tryApplyCachedRender(documentType: DocumentType, theme: RenderTheme): Boolean {
        val cached = renderCache[RenderCacheKey(documentType, theme)] ?: return false
        baseRendered = cached
        _uiState.value = _uiState.value.copy(rendered = cached)
        applySearchQuery(_uiState.value.searchQuery)
        return true
    }

    /** The surface the document is being drawn on: the reader's own override, else the app theme. */
    private fun currentTheme(): RenderTheme = RenderTheme(
        renderDarkModeOverride ?: isDarkTheme(_uiState.value.userPreferences, systemDarkTheme)
    )

    /**
     * The read failure, with whatever the exception gave us appended. The class name
     * and message are diagnostics rather than prose, so they are passed in as
     * arguments instead of being spelled out in the resource.
     */
    private fun describeReadFailure(readError: Exception?): UiMessage {
        if (readError == null) return UiMessage(R.string.viewer_error_open_failed)
        val className = readError.javaClass.simpleName
        val message = readError.message?.takeIf { it.isNotBlank() }
            ?: return UiMessage(R.string.viewer_error_open_failed_class, listOf(className))
        return UiMessage(R.string.viewer_error_open_failed_detail, listOf(className, message))
    }

    private suspend fun resolveFileName(uri: Uri): String? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        try {
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    return@withContext it.getString(0)
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // Fall back to the last path segment if the provider can't be queried.
        }
        return@withContext uri.lastPathSegment
    }

    /** Only a hint: providers mislabel freely, so the resolver trusts the name over this. */
    private suspend fun resolveMimeType(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.getType(uri)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            null
        }
    }

    private suspend fun readTextFromUri(uri: Uri): String? {
        val bytes = DocumentSource.read(getApplication<Application>().contentResolver, uri)
            ?: return null
        // The viewer never writes, so it wants the text and not the encoding that came with it.
        // Off the main thread regardless: this decodes up to MAX_FILE_BYTES.
        return withContext(Dispatchers.Default) { TextCodec.decode(bytes).text }
    }

    private fun ensureReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        getApplication<Application>().contentResolver.tryTakePersistablePermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    private fun applySearchQuery(query: String) {
        val currentState = _uiState.value
        val activeViewMode = currentState.viewMode
        val baseText = when (activeViewMode) {
            ViewMode.Raw -> currentState.rawText
            ViewMode.Rendered -> baseRendered?.toString().orEmpty()
        }

        if (activeViewMode == ViewMode.Rendered && baseRendered == null) {
            matches = SearchMatches.find("", "")
            _uiState.value = currentState.copy(
                rawHighlighted = null,
                searchMatchCount = 0,
                searchMatchIndex = 0,
                searchMatches = null
            )
            return
        }

        if (query.isBlank()) {
            matches = SearchMatches.find("", "")
            _uiState.value = _uiState.value.copy(
                rendered = baseRendered,
                rawHighlighted = null,
                searchMatchCount = 0,
                searchMatchIndex = 0,
                searchMatches = null
            )
            return
        }

        matches = SearchMatches.find(baseText, query)
        val count = matches.count
        val activeIndex = _uiState.value.searchMatchIndex.coerceIn(0, (count - 1).coerceAtLeast(0))

                _uiState.value = when (activeViewMode) {
            ViewMode.Rendered -> _uiState.value.copy(
                rendered = baseRendered,
                rawHighlighted = null,
                searchMatches = matches,
                searchMatchCount = count,
                searchMatchIndex = if (count == 0) 0 else activeIndex
            )
            ViewMode.Raw -> _uiState.value.copy(
                rendered = baseRendered,
                rawHighlighted = null,
                searchMatches = matches,
                searchMatchCount = count,
                searchMatchIndex = if (count == 0) 0 else activeIndex
            )
        }
    }

    private fun clampActiveHeadingIndex(current: Int, headings: List<HeadingItem>): Int {
        if (headings.isEmpty()) return -1
        if (current < 0) return -1
        return current.coerceAtMost(headings.lastIndex)
    }

    companion object {
        /** Coalesces the burst of preference emissions a single theme switch produces. */
        private const val REBUILD_DEBOUNCE_MS = 50L

        private fun isDarkTheme(prefs: UserPreferences, isSystemDarkTheme: Boolean): Boolean {
            return when (prefs.appThemeMode) {
                AppThemeModePreference.System -> isSystemDarkTheme
                AppThemeModePreference.Dark -> true
                AppThemeModePreference.Light -> false
            }
        }

        fun factory(application: Application, uri: String?): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ViewerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ViewerViewModel(application, uri) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}

/**
 * The reader surface still draws styled text and nothing else, so this is where the other two
 * [ReaderContent] shapes stop. P4 retypes the surface and this narrowing goes with it — until
 * then no format produces a [ReaderContent.Html] or [ReaderContent.Table] to lose.
 */
private fun ReaderContent?.asSpans(): ReaderContent.Spans? = this as? ReaderContent.Spans
