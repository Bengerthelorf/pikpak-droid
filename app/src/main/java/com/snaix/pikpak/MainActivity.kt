package com.snaix.pikpak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.autofill.AutofillManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SeslProgressBar
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.oneuiproject.oneui.layout.ToolbarLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : MiniPlayerHostActivity() {
    internal val model: CloudModel by viewModels {
        viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                CloudModel(app, transferHistory = TransferHistory(app))
            }
        }
    }

    private lateinit var actions: CloudActions
    private lateinit var content: FrameLayout
    private lateinit var transferPicker: TransferPickerState
    private var workspace: WorkspaceView? = null
    private var login: LoginView? = null
    private var fixtureMode = false
    private var viewStage = -1
    private val pendingLinks = ArrayDeque<String>()
    private var incomingDialog: AlertDialog? = null
    private var restoredIncomingDialog: Bundle? = null

    private val upload = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = transferPicker.takeUpload()
        if (uris.isNotEmpty()) model.finishTransferPicker(target) { model.upload(uris, it) }
    }
    private val uploadTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = transferPicker.takeUpload()
        if (uri != null) model.finishTransferPicker(target) { model.uploadTree(uri, it) }
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selection = transferPicker.takeDownloadSelection()
        if (uri != null) model.finishTransferPicker(selection?.files?.singleOrNull()) {
            model.exportPicked(it, uri, selection?.accountScope)
        }
    }
    private val saveTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val files = transferPicker.takeDownloadSelection()?.files?.takeIf(List<CloudFile>::isNotEmpty)
        if (uri != null) model.finishTransferPicker(files) { model.exportTree(it, uri) }
    }
    private val hash = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.calculateHash(uri) { value ->
            actions.result("PikPak GCID", value, getString(R.string.ui_copy_gcid)) { actions.copy(value, "GCID") }
        }
    }
    private val localVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val title = withContext(Dispatchers.IO) { queryName(uri) } ?: getString(R.string.ui_local_video)
            startActivity(PlaybackActivity.localIntent(this@MainActivity, uri, title))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = appearancePreferences().themeMode().nightMode
        super.onCreate(savedInstanceState)
        transferPicker = TransferPickerState(cacheDir, savedInstanceState)
        enableEdgeToEdge()
        actions = productionActions()
        if (savedInstanceState != null) {
            savedInstanceState.getStringArrayList("pendingLinks")?.let(pendingLinks::addAll)
            restoredIncomingDialog = savedInstanceState.getBundle("incomingDialog")
        } else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            acceptLinkIntent(intent)
        }
        content = object : FrameLayout(this) {
            private var externalText = false
            override fun dispatchDragEvent(event: DragEvent): Boolean {
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED ->
                        externalText = event.localState == null && event.clipDescription?.hasMimeType("text/*") == true
                    // An editable child would otherwise insert a dropped magnet into the search or login field.
                    DragEvent.ACTION_DROP -> if (externalText) {
                        IncomingLinks.fromClip(event.clipData)?.let { return queueLink(it) }
                    }
                    DragEvent.ACTION_DRAG_ENDED -> externalText = false
                }
                val childHandled = super.dispatchDragEvent(event)
                return childHandled || (externalText && event.action != DragEvent.ACTION_DROP)
            }
        }
        setContentView(content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (workspace?.handleBack() == true) return
                if (InAppPlayback.hasSeparatePip(this@MainActivity) && moveTaskToBack(true)) return
                closeHostedMini()
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        lifecycleScope.launch {
            model.observe { model.theme }.collect { theme ->
                if (delegate.localNightMode != theme.nightMode) delegate.localNightMode = theme.nightMode
            }
        }
        lifecycleScope.launch {
            model.observe { model.starting to model.signedIn }.collect { (starting, signedIn) ->
                if (!fixtureMode) showStage(if (starting) 0 else if (signedIn) 2 else 1)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.observe { model.signedIn to model.page }.collectLatest { (signedIn, page) ->
                    if (signedIn && page == Page.Tasks) model.pollTasks()
                }
            }
        }
        lifecycleScope.launch {
            PreviewWarmupService.indexCommits.collect {
                if (!fixtureMode) model.reloadCachedFolderStats()
            }
        }
        lifecycleScope.launch {
            model.observe { model.notice }.collect { message ->
                if (message != null) {
                    if (message.length < 45) Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    else actions.result(getString(R.string.ui_action_result), message)
                    model.notice = null
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.observe { model.signedIn && !model.starting && model.busy == null }.collect { ready ->
                    if (ready) showPendingLink()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        transferPicker.saveInstanceState(outState)
        outState.putStringArrayList("pendingLinks", ArrayList(pendingLinks))
        outState.putBundle("incomingDialog", incomingDialog?.takeIf { it.isShowing }?.onSaveInstanceState() ?: restoredIncomingDialog)
    }

    override fun onDestroy() {
        incomingDialog?.setOnDismissListener(null)
        incomingDialog?.dismiss()
        if (isFinishing) transferPicker.clear()
        super.onDestroy()
    }

    private fun showStage(stage: Int, screenModel: CloudModel = model, screenActions: CloudActions = actions) {
        if (viewStage == stage) return
        if (viewStage == 2 && stage == 1) {
            InAppPlayback.closeCloudPlayback()
            closeHostedMini()
        }
        viewStage = stage
        actions.dismissDialogs()
        workspace?.dispose()
        workspace = null
        login?.dispose()
        login = null
        content.removeAllViews()
        val next = when (stage) {
            0 -> FrameLayout(this).apply {
                val spinner = SeslProgressBar(context, null, 0, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar).apply {
                    isIndeterminate = true
                }
                addView(spinner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }
            1 -> {
                login = LoginView(this, screenModel, screenActions)
                login!!.root
            }
            else -> {
                workspace = WorkspaceView(this, screenModel, screenActions)
                workspace!!.root
            }
        }
        content.addView(next)
        if (stage == 2) showPendingLink()
    }

    internal fun showFixture(fixture: CloudModel, signedIn: Boolean = true) {
        fixtureMode = true
        viewStage = -1
        showStage(if (signedIn) 2 else 1, fixture, CloudActions(this, fixture))
    }

    private fun productionActions() = CloudActions(this, model).apply {
        chooseUpload = { folder -> launchTransferPicker {
            transferPicker.saveUpload(folder)
            upload.launch(arrayOf("*/*"))
        } }
        chooseUploadTree = { folder -> launchTransferPicker {
            transferPicker.saveUpload(folder)
            uploadTree.launch(null)
        } }
        chooseSave = { file -> launchTransferPicker {
            transferPicker.saveDownload(listOf(file), model.transferAccountScope())
            save.launch(safeFileName(file.name))
        } }
        chooseTree = { files -> launchTransferPicker {
            transferPicker.saveDownload(files)
            saveTree.launch(null)
        } }
        chooseHash = { hash.launch(arrayOf("*/*")) }
        chooseLocalVideo = { localVideo.launch(arrayOf("video/*")) }
    }

    private fun launchTransferPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: Exception) {
            transferPicker.clear()
            model.notice = getString(R.string.ui_picker_failed)
        }
    }

    private fun queryName(uri: Uri) = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptLinkIntent(intent)
    }

    private fun acceptLinkIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return
        queueLink(IncomingLinks.fromIntent(intent))
    }

    private fun queueLink(link: String?): Boolean {
        if (link == null) {
            Toast.makeText(this, getString(R.string.ui_no_shared_url), Toast.LENGTH_SHORT).show()
            return false
        }
        if (link in pendingLinks) return true
        // Keep the activity's saved-state transaction small even if several links arrive while signing in.
        if (link.length + pendingLinks.sumOf { it.length } > 64 * 1024) {
            Toast.makeText(this, getString(R.string.ui_shared_queue_limit), Toast.LENGTH_SHORT).show()
            return false
        }
        pendingLinks.addLast(link)
        showPendingLink()
        return true
    }

    private fun showPendingLink() {
        val link = pendingLinks.firstOrNull() ?: return
        if (viewStage != 2 || !model.signedIn || model.busy != null || incomingDialog != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing || isDestroyed) return
        val dialog = (if (containsPikPakShare(link)) actions.openShare(link) else actions.addTask(initial = link)) ?: return
        incomingDialog = dialog
        restoredIncomingDialog?.let(dialog::onRestoreInstanceState)
        restoredIncomingDialog = null
        dialog.setOnDismissListener {
            incomingDialog = null
            if (model.signedIn) pendingLinks.removeFirstOrNull()
            window.decorView.post { showPendingLink() }
        }
    }
}

private class LoginView(
    activity: MainActivity,
    private val model: CloudModel,
    private val actions: CloudActions,
) {
    val root = ToolbarLayout(activity).apply {
        setTitle(activity.getString(R.string.app_name))
        showNavigationButton = false
        isExpandable = true
        setExpanded(true, false)
    }
    private val content = activity.column().apply { setPadding(activity.dp(24), activity.dp(16), activity.dp(24), activity.dp(22)) }
    private val message = activity.label("", 14f).apply { setTextColor(activity.themeColor(com.google.android.material.R.attr.colorError)) }
    private val email = field(activity, activity.getString(R.string.ui_email), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    private val password = field(activity, activity.getString(R.string.ui_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
    private val remember = AppCompatCheckBox(activity).apply {
        text = activity.getString(R.string.ui_remember_sign_in)
    }
    private val signIn = activity.button(activity.getString(R.string.ui_sign_in)) {
        val emailValue = email.editText?.text?.toString().orEmpty()
        val passwordValue = password.editText?.text?.toString().orEmpty()
        if (emailValue.isNotBlank() && passwordValue.isNotEmpty()) {
            WindowCompat.getInsetsController(activity.window, content).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            model.login(emailValue, passwordValue, remember.isChecked) {
                runCatching { activity.getSystemService(AutofillManager::class.java)?.commit() }
                password.editText?.text?.clear()
            }
        } else {
            message.text = activity.getString(R.string.ui_missing_credentials)
            message.showWhen(true)
        }
    }
    private val verification = activity.button(activity.getString(R.string.ui_verify_in_browser)) { model.challenge?.let(actions::openVerification) }
    private val retrySession = activity.button(activity.getString(R.string.ui_retry_session), model::restoreSession)
    private val manualLogin = activity.button(activity.getString(R.string.ui_sign_in_password), model::useLoginForm)
    private val job: Job

    init {
        val heading = activity.label("", 16f).apply {
            setPadding(0, 0, 0, activity.dp(17))
        }
        content.addFullWidth(heading)
        listOf(email, password, remember, message, verification, retrySession, manualLogin, signIn).forEach { view ->
            content.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = activity.dp(10) })
        }
        val utility = activity.row()
        utility.addView(activity.button(activity.getString(R.string.ui_local_gcid)) { actions.chooseHash() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        utility.addView(activity.button(activity.getString(R.string.ui_appearance), actions::theme), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        content.addFullWidth(utility)
        root.addView(ScrollView(activity).apply { addView(content) }, ToolbarLayout.ToolbarLayoutParams(MATCH_PARENT, MATCH_PARENT))
        job = activity.lifecycleScope.launch {
            model.observe { LoginState(model.busy, model.authError, model.challenge, model.canRetrySession) }.collect {
                (busy, error, challenge, canRetrySession) ->
                heading.text = if (canRetrySession) activity.getString(R.string.ui_session_unavailable) else activity.getString(R.string.ui_sign_in_cloud_intro)
                listOf(email, password, remember, signIn).forEach { it.showWhen(!canRetrySession) }
                manualLogin.showWhen(canRetrySession && busy == null)
                signIn.isEnabled = busy == null
                signIn.text = busy ?: activity.getString(R.string.ui_sign_in)
                email.isEnabled = busy == null
                password.isEnabled = busy == null
                remember.isEnabled = busy == null
                message.text = error.orEmpty()
                message.showWhen(error != null)
                verification.showWhen(challenge != null)
                retrySession.showWhen(canRetrySession && busy == null)
            }
        }
    }

    private data class LoginState(val busy: String?, val error: String?, val challenge: String?, val canRetrySession: Boolean)

    private fun field(activity: MainActivity, title: String, kind: Int, password: Boolean = false) =
        TextInputLayout(activity).apply {
            hint = title
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(TextInputEditText(context).apply {
                id = if (password) R.id.login_password else R.id.login_email
                inputType = kind
                isSingleLine = true
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                if (password) setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                else setAutofillHints(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS)
                isSaveEnabled = !password
            })
        }

    fun dispose() { job.cancel() }
}
