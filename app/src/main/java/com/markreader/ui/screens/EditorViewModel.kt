package com.markreader.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.markreader.ui.editor.EditorDocumentSession
import com.markreader.ui.editor.EditorRevisionState
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.markreader.R
import com.markreader.file.DocumentSource
import com.markreader.file.FileTooLargeException
import com.markreader.file.DecodedText
import com.markreader.file.TextEncoding
import com.markreader.file.TextCodec
import com.markreader.file.tryTakePersistablePermission
import com.markreader.data.AppThemeModePreference
import com.markreader.data.PreferencesRepository
import com.markreader.data.RecentFilesRepository
import com.markreader.data.UserPreferences
import com.markreader.file.DocumentType
import com.markreader.file.DocumentTypeResolver
import com.markreader.format.DocumentFormat
import com.markreader.format.FormatCapabilities
import com.markreader.format.FormatRegistry
import com.markreader.format.ReaderContent
import com.markreader.format.RenderTheme
import com.markreader.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default for a format that has nothing more specific to ask the picker for. */
private const val PLAIN_TEXT_MIME = "text/plain"

enum class EditorTab { Edit, Preview }

sealed class SaveResult {
    data object Success : SaveResult()
    data object NoPermission : SaveResult()

    /**
     * The document was decoded lossily, so its bytes cannot be reproduced. Writing would
     * save the replacement characters over whatever the original bytes were. Save As is
     * the remedy: a new file, honestly UTF-8, with the original left alone.
     */
    data object UnknownEncoding : SaveResult()
}

data class EditorUiState(
    val isLoading: Boolean = true,
    val fileName: String = "",
    /** What the editor may offer, decided by the document's format rather than by its type. */
    val capabilities: FormatCapabilities = FormatCapabilities(),
    /** What an empty document of this format invites the user to type. */
    @StringRes val placeholderRes: Int = R.string.editor_placeholder_text,
    /** The MIME type Save As asks the system picker for. */
    val creationMimeType: String = PLAIN_TEXT_MIME,
    val isModified: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val previewText: ReaderContent? = null,
    /** Null once a document has loaded means it cannot be written back — see [SaveResult.UnknownEncoding]. */
    val encoding: TextEncoding? = null,
    val activeTab: EditorTab = EditorTab.Edit,
    val userPreferences: UserPreferences = UserPreferences(),
    val errorMessage: UiMessage? = null
)

class EditorViewModel(
    application: Application,
    private val initialUri: String?
) : AndroidViewModel(application) {

    private val repository = PreferencesRepository.getInstance(application)
    private val recentFilesRepository = RecentFilesRepository.getInstance(application)
    private var currentUri: String? = initialUri
    private var systemDarkTheme = false
    private val formats = FormatRegistry(application)
    private var format: DocumentFormat? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Mutable native buffer is deliberately excluded from StateFlow equality and recomposition.
    val documentSession = EditorDocumentSession()
    private val revisions = EditorRevisionState()
    private var previewJob: Job? = null
    // Markwon parsing is not cooperatively cancellable. Never overlap parsers after cancellation.
    private val previewMutex = Mutex()

    init {
        documentSession.onChanged = {
            // Selection/composition-span changes never arrive here. No text read, copy or parse.
            invalidatePreview()
            if (!_uiState.value.isModified || _uiState.value.previewText != null) {
                _uiState.value = _uiState.value.copy(isModified = true, previewText = null)
            }
        }
        observePreferences()
        if (initialUri != null) {
            loadContent()
        } else {
            // No document to resolve a type from, and this app's default is Markdown.
            _uiState.value = _uiState.value
                .withFormat(formats.formatFor(DocumentType.Markdown))
                .copy(
                    isLoading = false,
                    fileName = UNTITLED_MARKDOWN,
                    encoding = TextEncoding.Utf8
                )
        }
    }

    fun onSystemDarkThemeChanged(isDark: Boolean) {
        if (systemDarkTheme == isDark) return
        systemDarkTheme = isDark
        formats.invalidateRenderers()
        requestPreview()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            repository.preferences.collect { prefs ->
                val themeChanged = _uiState.value.userPreferences.appThemeMode != prefs.appThemeMode
                _uiState.value = _uiState.value.copy(userPreferences = prefs)
                if (themeChanged) requestPreview()
            }
        }
    }

    private fun loadContent() {
        viewModelScope.launch {
            val uri = Uri.parse(initialUri)
            val fileName = resolveFileName(uri)
                ?: getApplication<Application>().getString(R.string.untitled)
            var tooLarge = false
            val decoded = try {
                readDocument(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileTooLargeException) {
                tooLarge = true
                null
            } catch (e: Exception) {
                null
            }
            documentSession.replaceAll(decoded?.text ?: "")
            revisions.reset(documentSession.revision)
            _uiState.value = _uiState.value.withFormatFor(fileName).copy(
                isLoading = false,
                fileName = fileName,
                encoding = decoded?.encoding,
                errorMessage = when {
                    tooLarge -> UiMessage(R.string.editor_error_file_too_large)
                    decoded == null -> UiMessage(R.string.editor_error_read_failed)
                    else -> null
                }
            )
            requestPreview()
        }
    }

    fun onTabChanged(tab: EditorTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
        requestPreview()
    }

    // Formatting helpers

    fun formatBold() = wrapSelection("**", "**")
    fun formatItalic() = wrapSelection("*", "*")
    fun formatStrikethrough() = wrapSelection("~~", "~~")
    fun formatHeading(level: Int) = insertLinePrefix("#".repeat(level) + " ")
    fun formatBullet() = insertLinePrefix("- ")
    fun formatNumbered() = insertLinePrefix("1. ")
    fun formatHorizontalRule() = insertAtCursor("\n\n---\n\n")
    fun formatInlineCode() = wrapSelection("`", "`")
    fun formatCodeBlock() = wrapSelection("\n```\n", "\n```\n")
    fun formatBlockquote() = insertLinePrefix("> ")
    fun formatLink() = wrapSelection("[", "](url)")

    private fun wrapSelection(prefix: String, suffix: String) =
        documentSession.wrapSelection(prefix, suffix)

    private fun insertLinePrefix(prefix: String) = documentSession.insertLinePrefix(prefix)

    private fun insertAtCursor(insertion: String) = documentSession.insertAtCursor(insertion)

    fun save() {
        if (_uiState.value.isLoading || revisions.isSaving) return
        val uri = Uri.parse(currentUri ?: return)
        // A lossy decode cannot safely overwrite the source. Save As uses known UTF-8 instead.
        val encoding = _uiState.value.encoding ?: run {
            _uiState.value = _uiState.value.copy(saveResult = SaveResult.UnknownEncoding)
            return
        }
        saveSnapshot(uri, encoding, saveAs = false)
    }

    fun onSaveAs(newUri: Uri) {
        if (_uiState.value.isLoading || revisions.isSaving) return
        saveSnapshot(newUri, _uiState.value.encoding ?: TextEncoding.Utf8, saveAs = true)
    }

    private fun saveSnapshot(uri: Uri, encoding: TextEncoding, saveAs: Boolean) {
        // Both accesses are main-thread confined: this pair describes one immutable snapshot.
        val revision = documentSession.revision
        val content = documentSession.snapshot()
        if (!revisions.beginSave(revision)) return
        _uiState.value = _uiState.value.copy(isSaving = true, saveResult = null)
        viewModelScope.launch {
            var written = false
            try {
                val bytes = withContext(Dispatchers.Default) { TextCodec.encode(content, encoding) }
                // Resolve metadata before the write: once it succeeds the new URI can be adopted
                // without another suspension (and cancellation cannot leave the old URI active).
                val newFileName = if (saveAs) {
                    resolveFileName(uri) ?: uri.lastPathSegment ?: _uiState.value.fileName
                } else null
                written = DocumentSource.write(
                    getApplication<Application>().contentResolver, uri, bytes
                )
                if (!written) {
                    _uiState.value = _uiState.value.copy(saveResult = SaveResult.NoPermission)
                    return@launch
                }
                if (newFileName != null) {
                    currentUri = uri.toString()
                    _uiState.value = _uiState.value.withFormatFor(newFileName).copy(
                        fileName = newFileName, encoding = encoding, previewText = null
                    )
                    requestPreview()
                }
                _uiState.value = _uiState.value.copy(saveResult = SaveResult.Success)
                if (saveAs) {
                    // Recent-files/grant bookkeeping must not turn a successful write into an error.
                    try {
                        getApplication<Application>().contentResolver.tryTakePersistablePermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        recentFilesRepository.recordOpen(uri.toString(), checkNotNull(newFileName))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(saveResult = SaveResult.NoPermission)
            } finally {
                revisions.finishSave(written)
                // A successful write saves its snapshot, NOT whatever the user has since typed.
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isModified = revisions.isModified(documentSession.revision)
                )
            }
        }
    }

    fun onSaveResultConsumed() {
        _uiState.value = _uiState.value.copy(saveResult = null)
    }

    private fun isPreviewShowing(): Boolean =
        _uiState.value.capabilities.hasEditorPreview &&
            _uiState.value.activeTab == EditorTab.Preview

    private fun invalidatePreview(): Long {
        previewJob?.cancel()
        previewJob = null
        return revisions.invalidatePreview()
    }

    /** Only tab/theme/format actions request work. Typing merely invalidates old work. */
    private fun requestPreview() {
        val generation = invalidatePreview()
        if (!isPreviewShowing() || _uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(previewText = null)
        previewJob = viewModelScope.launch {
            // A cancelled CPU parser may still run until its dispatcher returns. Await its lock
            // before copying another large buffer, so rapid tab/theme switches cannot pile up
            // full snapshots or concurrently use one non-thread-safe renderer.
            previewMutex.withLock {
                coroutineContext.ensureActive()
                val revision = documentSession.revision
                val content = documentSession.snapshot()
                val previewFormat = format
                val isDark = when (_uiState.value.userPreferences.appThemeMode) {
                    AppThemeModePreference.Dark -> true
                    AppThemeModePreference.Light -> false
                    AppThemeModePreference.System -> systemDarkTheme
                }
                val rendered: ReaderContent? = try {
                    previewFormat?.loadEditorPreview(content, RenderTheme(isDark))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                coroutineContext.ensureActive()
                if (isPreviewShowing() && format === previewFormat &&
                    revisions.acceptsPreview(generation, revision, documentSession.revision)
                ) {
                    // Read current state AFTER suspension: never restore old dirty/save/theme state.
                    _uiState.value = _uiState.value.copy(previewText = rendered)
                }
            }
        }
    }

    /** As [withFormat], for the format a document named [fileName] resolves to. */
    private fun EditorUiState.withFormatFor(fileName: String): EditorUiState =
        withFormat(formats.formatFor(DocumentTypeResolver.resolveFromHints(fileName)))

    /**
     * The state that follows from [next] — and **adopts it as the current format**, which the
     * preview renders through. The side effect is why the two travel together: a state that said
     * "Markdown" while [format] still held the previous document's would show a preview tab that
     * rendered the wrong thing.
     *
     * Null cannot arrive from a name alone, since `resolveFromHints` never answers
     * [DocumentType.Binary]. The editor is honest about it rather than asserting, and plain text
     * is the right floor if it ever does.
     */
    private fun EditorUiState.withFormat(next: DocumentFormat?): EditorUiState {
        format = next
        return copy(
            capabilities = next?.capabilities ?: FormatCapabilities(),
            placeholderRes = next?.editorPlaceholderRes ?: R.string.editor_placeholder_text,
            creationMimeType = next?.creationMimeType ?: PLAIN_TEXT_MIME
        )
    }

    private suspend fun resolveFileName(uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        try {
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use { if (it.moveToFirst()) return@withContext it.getString(0) }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) { }
        return@withContext uri.lastPathSegment
    }

    private suspend fun readDocument(uri: Uri): DecodedText? {
        val bytes = DocumentSource.read(getApplication<Application>().contentResolver, uri)
            ?: return null
        return withContext(Dispatchers.Default) { TextCodec.decode(bytes) }
    }

    companion object {
        private const val UNTITLED_MARKDOWN = "untitled.md"

        fun factory(application: Application, uri: String?): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return EditorViewModel(application, uri) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}
