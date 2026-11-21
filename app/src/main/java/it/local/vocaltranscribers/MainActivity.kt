package it.local.vocaltranscribers

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var statusView: TextView? = null
    private var transcriptView: TextView? = null
    private val modelChoiceViews = mutableListOf<ModelChoiceViews>()
    private var progressView: ProgressBar? = null
    private var stopTranscriptionButton: Button? = null
    private var transcriptionMetricsPanel: View? = null
    private var audioMetricValue: TextView? = null
    private var wordsMetricValue: TextView? = null
    private var timeMetricValue: TextView? = null
    private var copyButton: Button? = null
    private var shareButton: Button? = null
    private var pendingAudio: Uri? = null
    private var hasTranscriptionSession = false
    private var currentTranscript = ""
    private var selectedModel: AsrModel? = null
    private var pendingModelSelection: AsrModel? = null
    private var pendingModelSelectionExpiresAt = 0L
    private var statusMessage = "Seleziona un profilo nelle impostazioni per iniziare."
    private var progressValue: Int? = null
    private var downloadingModel = false
    private var progressOwner = ProgressOwner.NONE
    private val mainHandler = Handler(Looper.getMainLooper())
    private var transcriptionStarted = false
    private var transcriptionCancelled = false
    private var cancellationAvailable = false
    private var audioDurationMs = 0L
    private var conversionDurationMs = 0L
    private var asrMessenger: Messenger? = null
    private var asrProcessId = 0
    private var asrBound = false
    private var asrConnection: ServiceConnection? = null
    private val consoleTypeface by lazy(LazyThreadSafetyMode.NONE) { resources.getFont(R.font.sharetech_mono) }
    private val orbitronTypeface by lazy(LazyThreadSafetyMode.NONE) { resources.getFont(R.font.orbitron_bold) }
    private val asrReplyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        handleAsrMessage(message)
        true
    })
    private val revealCancelOptions = Runnable { revealTranscriptionActions() }
    private val resetModelSelectionConfirmation = Runnable {
        pendingModelSelection = null
        pendingModelSelectionExpiresAt = 0L
        updateModelChoiceViews()
    }
    @Volatile private var busy = false

    private data class ModelChoiceViews(
        val model: AsrModel,
        val row: View,
        val marker: TextView,
        val indicator: ModelStateIndicatorView,
    )

    private enum class ProgressOwner {
        NONE,
        TRANSCRIPTION,
    }

    private enum class ModelBadgeState {
        SELECT,
        CONFIRM,
        ACTIVE,
        DOWNLOAD,
    }

    private inner class ModelStateIndicatorView(context: Context) : View(context) {
        private var state = ModelBadgeState.SELECT
        private var downloadPercent: Int? = null
        private var sweepPosition = 0f
        private var sweepAnimator: ValueAnimator? = null
        private val density = resources.displayMetrics.density

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = consoleTypeface
            isFakeBoldText = true
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF173B4B.toInt() }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_CYAN }
        private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCB7FBFF.toInt() }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF70D9E7.toInt()
            strokeWidth = dp(1).toFloat()
        }

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        }

        fun setIndicatorState(newState: ModelBadgeState, percent: Int?) {
            state = newState
            downloadPercent = percent?.coerceIn(0, 100)
            updateSweepAnimation()
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            updateSweepAnimation()
        }

        override fun onDetachedFromWindow() {
            sweepAnimator?.cancel()
            sweepAnimator = null
            sweepPosition = 0f
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(84), dp(42))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = px(1f)
            val frame = framePath(inset, inset, width - inset, height - inset, px(4f))
            backgroundPaint.color = when (state) {
                ModelBadgeState.SELECT -> SURFACE
                ModelBadgeState.CONFIRM -> 0xFF3D321C.toInt()
                ModelBadgeState.ACTIVE -> 0xFF0D3341.toInt()
                ModelBadgeState.DOWNLOAD -> 0xFF091F2C.toInt()
            }
            framePaint.color = when (state) {
                ModelBadgeState.SELECT -> BORDER
                ModelBadgeState.CONFIRM -> ACCENT_GOLD
                ModelBadgeState.ACTIVE, ModelBadgeState.DOWNLOAD -> ACCENT_CYAN
            }
            framePaint.strokeWidth = px(if (state == ModelBadgeState.SELECT) 1f else 1.4f)
            canvas.drawPath(frame, backgroundPaint)
            canvas.drawPath(frame, framePaint)

            when (state) {
                ModelBadgeState.SELECT -> drawLabel(canvas, "SELECT", TEXT_SECONDARY, 9f)
                ModelBadgeState.CONFIRM -> drawLabel(canvas, "Confirm", ACCENT_GOLD, 9f)
                ModelBadgeState.ACTIVE -> {
                    drawLabel(canvas, "ACTIVE", ACCENT_CYAN, 9f)
                    val centerX = width / 2f
                    canvas.drawCircle(centerX - px(29f), height / 2f, px(1.4f), fillPaint)
                    canvas.drawCircle(centerX + px(29f), height / 2f, px(1.4f), fillPaint)
                }
                ModelBadgeState.DOWNLOAD -> drawDownloadIndicator(canvas)
            }
        }

        private fun drawDownloadIndicator(canvas: Canvas) {
            textPaint.textSize = sp(7f)
            textPaint.color = TEXT_SECONDARY
            canvas.drawText("DOWNLOAD", width / 2f, px(11f), textPaint)

            textPaint.textSize = sp(10f)
            textPaint.color = ACCENT_CYAN
            val readout = downloadPercent?.let { "$it%" } ?: "SYNC"
            canvas.drawText(readout, width / 2f, px(24f), textPaint)

            val left = px(7f)
            val right = width - px(7f)
            val top = height - px(8f)
            val bottom = height - px(5f)
            val track = RectF(left, top, right, bottom)
            canvas.drawRect(track, trackPaint)

            val percent = downloadPercent
            if (percent != null && percent > 0) {
                val filled = left + track.width() * percent / 100f
                canvas.drawRect(left, top, filled, bottom, fillPaint)
            }

            for (tick in 1..3) {
                val x = left + track.width() * tick / 4f
                canvas.drawLine(x, top - px(1f), x, bottom + px(1f), tickPaint)
            }

            val saveCount = canvas.save()
            canvas.clipRect(track)
            val beamWidth = px(10f)
            val beamStart = left - beamWidth + (track.width() + beamWidth * 2) * sweepPosition
            canvas.drawRoundRect(
                RectF(beamStart, top - px(1f), beamStart + beamWidth, bottom + px(1f)),
                px(1.5f),
                px(1.5f),
                scanPaint,
            )
            canvas.restoreToCount(saveCount)
        }

        private fun drawLabel(canvas: Canvas, label: String, color: Int, sizeSp: Float) {
            textPaint.textSize = sp(sizeSp)
            textPaint.color = color
            val metrics = textPaint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, width / 2f, baseline, textPaint)
        }

        private fun framePath(left: Float, top: Float, right: Float, bottom: Float, cut: Float) = Path().apply {
            moveTo(left + cut, top)
            lineTo(right - cut, top)
            lineTo(right, top + cut)
            lineTo(right, bottom - cut)
            lineTo(right - cut, bottom)
            lineTo(left + cut, bottom)
            lineTo(left, bottom - cut)
            lineTo(left, top + cut)
            close()
        }

        private fun updateSweepAnimation() {
            if (state == ModelBadgeState.DOWNLOAD && isAttachedToWindow) {
                if (sweepAnimator != null) return
                sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        sweepPosition = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                sweepAnimator?.cancel()
                sweepAnimator = null
                sweepPosition = 0f
            }
        }

        private fun px(value: Float): Float = value * density
        private fun sp(value: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedModel = preferences().getInt(KEY_MODEL_INDEX, NO_MODEL_INDEX)
            .takeIf { it in AsrModel.entries.indices }
            ?.let(AsrModel.entries::get)
        statusMessage = selectedModel?.let {
            "Profilo memorizzato: ${it.label}. I vocali condivisi useranno questa scelta."
        } ?: "Seleziona un profilo nelle impostazioni per iniziare."
        showTranscriptionScreen()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun showFlowScreen() {
        progressOwner = ProgressOwner.NONE
        progressView = null
        statusView = null
        stopTranscriptionButton = null
        val (page, scroll) = createPage()
        page.addView(spaceScene(), matchWrap())
        page.addView(consoleStrip("FLIGHT PROCEDURE", "03 STEPS"), matchWrap())
        page.addView(eyebrow("GUIDA DI BORDO  /  PROCEDURA"), matchWrap())
        page.addView(title("Come funziona"), matchWrap())
        page.addView(accentRule())
        page.addView(bodyText("Tre passaggi per passare da un vocale WhatsApp al testo, tutto sul telefono.", 15f), matchWrap())

        val steps = listOf(
            "01  /  WHATSAPP" to "Apri la chat e individua il messaggio vocale da trascrivere.",
            "02  /  CONDIVISIONE" to "Tocca Condividi e scegli “Trascrivi vocali in italiano”.",
            "03  /  TRASCRIZIONE" to "L’app usa il modello selezionato e mostra il testo. Puoi copiarlo o condividerlo.",
        )
        steps.forEachIndexed { index, (heading, description) ->
            val stepPanel = panel().apply {
                addView(eyebrow("${heading.substringBefore("  /")}   /   ${heading.substringAfter("  /  ")}"), matchWrap())
                addView(bodyText(description, 16f), matchWrap())
            }
            page.addView(stepPanel, matchWrap())
        }
        page.addView(
            backButton { showTranscriptionScreen() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
        setContentView(scroll)
        attachInsets(scroll, page)
    }

    private fun guideButton() = Button(this).apply {
        text = "GUIDA DI BORDO"
        textSize = 13f
        letterSpacing = 0.06f
        typeface = consoleTypeface
        setTextColor(TEXT_PRIMARY)
        backgroundTintList = null
        background = ConsoleFrameDrawable(SURFACE, ACCENT_BLUE, ACCENT_GOLD, resources.displayMetrics.density)
        minHeight = dp(58)
        setOnClickListener { showFlowScreen() }
    }

    private fun backButton(action: () -> Unit) = actionButton("Indietro", action).apply {
        transformationMethod = null
        setTextColor(TEXT_PRIMARY)
        background = ConsoleFrameDrawable(SURFACE, BACK_NAV_ACCENT, ACCENT_BLUE, resources.displayMetrics.density)
    }

    private fun showSettingsScreen() {
        progressOwner = ProgressOwner.NONE
        progressView = null
        statusView = null
        stopTranscriptionButton = null
        val (page, scroll) = createPage()
        modelChoiceViews.clear()
        page.addView(consoleStrip("MODEL BAY", "LOCAL CORE / 05"), matchWrap())
        page.addView(eyebrow("PLANCIA  /  CONFIGURAZIONE DEL SISTEMA"), matchWrap())
        page.addView(title("Scegli il modello"), matchWrap())
        page.addView(accentRule())
        page.addView(bodyText(
            "Imposta il profilo prima di condividere i vocali. La scelta viene ricordata.",
            14f,
        ), matchWrap())
        AsrModel.entries.forEach { model ->
            val option = modelOption(model)
            option.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9) }
            page.addView(option)
        }

        page.addView(
            backButton { showTranscriptionScreen() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )

        setContentView(scroll)
        attachInsets(scroll, page)
        renderProgress()
    }

    private fun showTranscriptionScreen() {
        progressOwner = ProgressOwner.TRANSCRIPTION
        progressView = null
        statusView = null
        stopTranscriptionButton = null
        val (page, scroll) = createPage()
        page.addView(spaceScene(), matchWrap())
        page.addView(consoleStrip("INFERENCE CORE", "CPU / LOCAL"), matchWrap())
        val firstRun = selectedModel == null && !hasTranscriptionSession
        page.addView(
            eyebrow(if (firstRun) "BENVENUTO  /  SISTEMA PRONTO" else "HOME  /  ELABORAZIONE LOCALE"),
            matchWrap(),
        )
        page.addView(title(if (firstRun) "Benvenuto a bordo" else "Trascrivi"), matchWrap())
        page.addView(accentRule())
        val modelButton = actionButton(if (selectedModel == null) "SCEGLI UN MODELLO" else "CAMBIA MODELLO") {
            showSettingsScreen()
        }
        val guideNavigationButton = guideButton()

        if (firstRun) {
            page.addView(
                bodyText(
                    "Questa applicazione può ricevere i messaggi vocali da altre app ed è in grado di trascriverli come testo.",
                    15f,
                ),
                matchWrap(),
            )
            page.addView(
                bodyText(
                    "Nessun dato viene inviato su Internet: tutto avviene sul tuo stesso telefonino.",
                    15f,
                ),
                matchWrap(),
            )
            page.addView(
                bodyText("Per prima cosa, scegli un modello (potrai sempre cambiarlo in seguito).", 15f),
                matchWrap(),
            )
            page.addView(
                modelButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(9)
                },
            )
            page.addView(guideNavigationButton, matchWrap())
        } else {
            if (!hasTranscriptionSession) {
                page.addView(
                    modelButton,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(9)
                    },
                )
                page.addView(guideNavigationButton, matchWrap())
            }

            val homeMessage = when {
                downloadingModel -> "Download di ${selectedModel!!.label} in corso. Torna alla selezione del modello per seguirne l’avanzamento."
                hasTranscriptionSession -> statusMessage
                selectedModel == null -> "Scegli un modello per iniziare la trascrizione."
                !ModelFiles.isInstalled(this, selectedModel!!) -> "Il profilo ${selectedModel!!.label} non è installato. Selezionalo per scaricarlo."
                else -> "È tutto pronto. Ora condividi un messaggio vocale con questa app: verrà trascritto in testo."
            }
            statusView = bodyText(homeMessage, 14f).also {
                it.visibility = if (homeMessage.isBlank()) View.GONE else View.VISIBLE
                page.addView(it, matchWrap())
            }
        }

        if (hasTranscriptionSession) {
            progressView = progressBar().also { page.addView(it, matchWrap()) }
            stopTranscriptionButton = actionButton("INTERROMPI TRASCRIZIONE") { cancelTranscription() }.apply {
                visibility = if (busy && transcriptionStarted && cancellationAvailable) View.VISIBLE else View.GONE
            }
            page.addView(stopTranscriptionButton, matchWrap())

            val resultPanel = panel().apply {
                addView(eyebrow("TESTO RICONOSCIUTO"), matchWrap())
                transcriptView = bodyText(currentTranscript.ifBlank { "La trascrizione apparirà qui." }, 17f)
                    .apply { setTextIsSelectable(true) }
                addView(transcriptView, matchWrap())
            }
            page.addView(sectionLabel("RISULTATO"), matchWrap())
            page.addView(resultPanel, matchWrap())
            page.addView(transcriptionMetrics(), matchWrap())
            val textActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            copyButton = actionButton("COPIA TESTO") { copyTranscript() }.apply {
                textSize = 12f
                letterSpacing = 0f
            }
            shareButton = actionButton("CONDIVIDI TESTO") { shareTranscript() }.apply {
                textSize = 12f
                letterSpacing = 0f
            }
            textActions.addView(
                copyButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5)
                },
            )
            textActions.addView(
                shareButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5)
                },
            )
            page.addView(
                textActions,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                },
            )
            setActionEnabled(copyButton, currentTranscript.isNotBlank())
            setActionEnabled(shareButton, currentTranscript.isNotBlank())
            page.addView(
                modelButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(9)
                },
            )
            page.addView(guideNavigationButton, matchWrap())
        } else {
            transcriptView = null
            transcriptionMetricsPanel = null
            audioMetricValue = null
            wordsMetricValue = null
            timeMetricValue = null
            copyButton = null
            shareButton = null
        }

        setContentView(scroll)
        attachInsets(scroll, page)
        renderProgress()
    }

    private fun createPage(): Pair<LinearLayout, ScrollView> {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(20))
            background = ConsoleNightDrawable(BACKGROUND, ACCENT_BLUE, resources.displayMetrics.density)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        return page to scroll
    }

    private fun attachInsets(scroll: ScrollView, page: LinearLayout) {
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else insets.systemWindowInsetTop
            val bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else insets.systemWindowInsetBottom
            page.setPadding(dp(20), topInset + dp(14), dp(20), bottomInset + dp(20))
            insets
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        if (busy) {
            setStatus("Attendi il completamento dell’operazione in corso.")
            return
        }
        val uri = incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            showTranscriptionScreen()
            Toast.makeText(this, "WhatsApp non ha passato un file audio. Riprova con Condividi.", Toast.LENGTH_LONG).show()
            return
        }
        pendingAudio = uri
        hasTranscriptionSession = true
        currentTranscript = ""
        audioDurationMs = 0L
        conversionDurationMs = 0L
        transcriptView?.text = ""
        transcriptionMetricsPanel?.visibility = View.GONE
        setActionEnabled(copyButton, false)
        setActionEnabled(shareButton, false)
        val model = selectedModel
        if (model == null) {
            showSettingsScreen()
            Toast.makeText(this, "Vocale ricevuto. Scegli un modello: lo scarico e poi trascrivo questo audio.", Toast.LENGTH_LONG).show()
            return
        }
        if (!ModelFiles.isInstalled(this, model)) {
            statusMessage = "Il profilo ${model.label} non è ancora installato. Selezionalo per scaricarlo."
            showSettingsScreen()
            Toast.makeText(this, "Vocale ricevuto. Completa il download del profilo per trascriverlo.", Toast.LENGTH_LONG).show()
            return
        }
        statusMessage = "Vocale ricevuto. Avvio con il profilo memorizzato."
        showTranscriptionScreen()
        transcribePendingAudio(model)
    }

    private fun onModelSelected(model: AsrModel) {
        if (busy) return
        if (model == selectedModel && ModelFiles.isInstalled(this, model)) {
            clearPendingModelSelectionConfirmation()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (model == pendingModelSelection && now < pendingModelSelectionExpiresAt) {
            clearPendingModelSelectionConfirmation(refreshViews = false)
            selectedModel = model
            preferences().edit().putInt(KEY_MODEL_INDEX, model.ordinal).apply()
            installSelectedModel(model)
            return
        }

        mainHandler.removeCallbacks(resetModelSelectionConfirmation)
        pendingModelSelection = model
        pendingModelSelectionExpiresAt = now + MODEL_CONFIRMATION_TIMEOUT_MS
        mainHandler.postDelayed(resetModelSelectionConfirmation, MODEL_CONFIRMATION_TIMEOUT_MS)
        updateModelChoiceViews()
    }

    private fun clearPendingModelSelectionConfirmation(refreshViews: Boolean = true) {
        mainHandler.removeCallbacks(resetModelSelectionConfirmation)
        pendingModelSelection = null
        pendingModelSelectionExpiresAt = 0L
        if (refreshViews) updateModelChoiceViews()
    }

    private fun modelOption(model: AsrModel): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(90)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
        }
        val marker = TextView(this).apply {
            text = "◆"
            textSize = 16f
            typeface = consoleTypeface
            setPadding(0, 0, dp(12), 0)
            gravity = Gravity.CENTER
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val moduleTitle = TextView(this).apply {
            text = modelDisplayName(model)
            textSize = 16f
            letterSpacing = 0.025f
            typeface = orbitronTypeface
            setTextColor(TEXT_PRIMARY)
        }
        val moduleSpecs = TextView(this).apply {
            text = modelSpecs(model)
            textSize = 13f
            letterSpacing = 0.045f
            typeface = consoleTypeface
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(5), 0, 0)
        }
        textColumn.addView(moduleTitle, matchWrap())
        textColumn.addView(moduleSpecs, matchWrap())
        val indicator = ModelStateIndicatorView(this)
        row.addView(marker, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(indicator, LinearLayout.LayoutParams(dp(84), dp(42)))
        modelChoiceViews += ModelChoiceViews(model, row, marker, indicator)
        row.setOnClickListener { onModelSelected(model) }
        updateModelChoiceViews()
        return row
    }

    private fun updateModelChoiceViews() {
        modelChoiceViews.forEach { choice ->
            val selected = choice.model == selectedModel
            val downloading = selected && downloadingModel
            val active = selected && !downloading && ModelFiles.isInstalled(this, choice.model)
            val confirming = choice.model == pendingModelSelection && !busy
            val state = when {
                downloading -> ModelBadgeState.DOWNLOAD
                active -> ModelBadgeState.ACTIVE
                confirming -> ModelBadgeState.CONFIRM
                else -> ModelBadgeState.SELECT
            }
            choice.indicator.setIndicatorState(state, if (downloading) progressValue else null)
            val emphasized = active || downloading || confirming
            val edgeColor = when {
                confirming -> ACCENT_GOLD
                active || downloading -> ACCENT_CYAN
                else -> BORDER
            }
            val signalColor = when {
                active || confirming -> ACCENT_GOLD
                downloading -> ACCENT_CYAN
                else -> ACCENT_BLUE
            }
            choice.row.background = ConsoleFrameDrawable(
                if (emphasized) ACTIVE_SURFACE else SURFACE,
                edgeColor,
                signalColor,
                resources.displayMetrics.density,
            )
            choice.marker.setTextColor(if (confirming) ACCENT_GOLD else if (active || downloading) ACCENT_CYAN else ACCENT_BLUE)
            val stateDescription = when {
                downloading -> "download in corso${progressValue?.let { ", $it percento" }.orEmpty()}"
                active -> "Active"
                confirming -> "tocca di nuovo entro cinque secondi per confermare"
                else -> "Select per scegliere"
            }
            choice.row.contentDescription = "${modelDisplayName(choice.model)}, ${modelSpecs(choice.model)}, $stateDescription"
            choice.row.isEnabled = !busy
            choice.row.alpha = if (busy && !downloading) DISABLED_ALPHA else 1f
        }
    }

    private fun modelDisplayName(model: AsrModel): String = when (model) {
        AsrModel.VOSK -> "VOSK  /  ITALIAN"
        AsrModel.WHISPER_TINY -> "WHISPER  /  TINY INT8"
        AsrModel.WHISPER_BASE -> "WHISPER  /  BASE INT8"
        AsrModel.WHISPER_SMALL -> "WHISPER  /  SMALL INT8"
        AsrModel.WHISPER_MEDIUM -> "WHISPER  /  MEDIUM INT8"
    }

    private fun modelSpecs(model: AsrModel): String = when (model) {
        AsrModel.VOSK -> "50 MB  /  QUALITÀ SCARSA  /  ITALIANO"
        AsrModel.WHISPER_TINY -> "104 MB  /  QUALITÀ MOLTO BASSA  /  MULTILINGUE"
        AsrModel.WHISPER_BASE -> "160 MB  /  QUALITÀ BASSA  /  MULTILINGUE"
        AsrModel.WHISPER_SMALL -> "375 MB  /  QUALITÀ MEDIA  /  MULTILINGUE"
        AsrModel.WHISPER_MEDIUM -> "946 MB  /  QUALITÀ ALTA  /  MULTILINGUE"
    }

    private fun installSelectedModel(model: AsrModel) {
        progressValue = null
        downloadingModel = true
        busy = true
        setBusyUi(true)
        setStatus("Preparo ${model.label}…")
        executor.execute {
            try {
                ModelFiles.prepare(this, model, ::showDownloadProgress)
                runOnUiThread {
                    downloadingModel = false
                    progressValue = null
                    setBusyUi(false)
                    val audioWaitingForFirstModel = pendingAudio != null
                    if (audioWaitingForFirstModel) {
                        statusMessage = "${model.label} pronto. Avvio la trascrizione del vocale ricevuto."
                        showTranscriptionScreen()
                        transcribePendingAudio(model)
                    } else {
                        statusMessage = "${model.label} pronto."
                        Toast.makeText(
                            this,
                            "Download completato. Ora condividi un vocale con questa app",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    downloadingModel = false
                    val message = "Non riesco a installare ${model.label}: ${error.message ?: "errore di download"}"
                    setStatus(message)
                    progressValue = null
                    setBusyUi(false)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun transcribePendingAudio(model: AsrModel) {
        val uri = pendingAudio ?: return
        if (busy) return
        if (!ModelFiles.isInstalled(this, model)) {
            statusMessage = "Il profilo " + model.label + " non è installato. Scaricalo dalla selezione modelli."
            showSettingsScreen()
            return
        }

        transcriptionCancelled = false
        transcriptionStarted = false
        cancellationAvailable = false
        audioDurationMs = 0L
        conversionDurationMs = 0L
        asrProcessId = 0
        setBusyUi(true)
        currentTranscript = ""
        transcriptView?.text = ""
        transcriptionMetricsPanel?.visibility = View.GONE
        stopTranscriptionButton?.visibility = View.GONE
        setActionEnabled(copyButton, false)
        setActionEnabled(shareButton, false)
        progressValue = null
        setStatus("Avvio la trascrizione con " + model.label + "…")

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                if (transcriptionCancelled) return
                if (binder == null) {
                    failTranscription("Android non ha collegato il motore locale.")
                    return
                }
                asrMessenger = Messenger(binder)
                val request = Message.obtain(null, TranscriptionService.MSG_TRANSCRIBE).apply {
                    data = Bundle().apply {
                        putString(TranscriptionService.KEY_AUDIO_URI, uri.toString())
                        putString(TranscriptionService.KEY_MODEL, model.key)
                        putString(
                            TranscriptionService.KEY_MODEL_DIRECTORY,
                            File(filesDir, "models/" + model.key).absolutePath,
                        )
                    }
                    replyTo = asrReplyMessenger
                }
                try {
                    asrMessenger?.send(request)
                } catch (error: Exception) {
                    failTranscription("Non riesco ad avviare il motore locale: " + (error.message ?: "errore di comunicazione"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!transcriptionCancelled && busy) {
                    failTranscription("Il processo di trascrizione si è interrotto.")
                }
            }
        }
        asrConnection = connection
        val bound = bindService(
            Intent(this, TranscriptionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        asrBound = bound
        if (!bound) {
            failTranscription("Android non è riuscito ad avviare il motore locale.")
        }
    }

    private fun handleAsrMessage(message: Message) {
        if (transcriptionCancelled) return
        when (message.what) {
            TranscriptionService.MSG_STARTED -> {
                transcriptionStarted = true
                cancellationAvailable = false
                asrProcessId = message.arg1
                setStatus("Trascrizione audio in corso…")
                mainHandler.removeCallbacks(revealCancelOptions)
                mainHandler.postDelayed(revealCancelOptions, CANCEL_AFTER_MS)
            }
            TranscriptionService.MSG_COMPLETE -> {
                mainHandler.removeCallbacks(revealCancelOptions)
                val result = message.data.getString(TranscriptionService.KEY_RESULT).orEmpty()
                audioDurationMs = message.data.getLong(TranscriptionService.KEY_AUDIO_DURATION_MS)
                conversionDurationMs = message.data.getLong(TranscriptionService.KEY_ELAPSED_MS)
                currentTranscript = result
                transcriptView?.text = result.ifBlank { "Non ho riconosciuto parole nel vocale." }
                updateTranscriptionMetrics(result)
                setStatus("")
                setActionEnabled(copyButton, result.isNotBlank())
                setActionEnabled(shareButton, result.isNotBlank())
                pendingAudio = null
                cancellationAvailable = false
                stopTranscriptionButton?.visibility = View.GONE
                setBusyUi(false)
                releaseAsrService(killProcess = false)
            }
            TranscriptionService.MSG_ERROR -> {
                val details = message.data.getString(TranscriptionService.KEY_ERROR)
                failTranscription("Non sono riuscito a trascrivere questo audio: " + (details ?: "formato non supportato"))
            }
        }
    }

    private fun revealTranscriptionActions() {
        if (!busy || !transcriptionStarted) return
        cancellationAvailable = true
        stopTranscriptionButton?.visibility = View.VISIBLE
        setStatus(
            "La conversione di questo vocale, per via della lunghezza e del modello di trascrizione, " +
                "sta impiegando più tempo del previsto. Puoi interrompere, scegliere un profilo inferiore e riprovare.",
        )
    }

    private fun cancelTranscription() {
        if (!busy || !transcriptionStarted) return
        transcriptionCancelled = true
        cancellationAvailable = false
        mainHandler.removeCallbacks(revealCancelOptions)
        releaseAsrService(killProcess = true)
        progressValue = null
        setBusyUi(false)
        stopTranscriptionButton?.visibility = View.GONE
        setStatus("Trascrizione interrotta. Puoi scegliere un profilo inferiore e riprovare.")
    }

    private fun failTranscription(message: String) {
        if (transcriptionCancelled) return
        mainHandler.removeCallbacks(revealCancelOptions)
        cancellationAvailable = false
        releaseAsrService(killProcess = false)
        progressValue = null
        stopTranscriptionButton?.visibility = View.GONE
        setStatus(message)
        setBusyUi(false)
    }

    private fun releaseAsrService(killProcess: Boolean) {
        if (killProcess && asrProcessId > 0) {
            runCatching { Process.killProcess(asrProcessId) }
        }
        asrProcessId = 0
        asrMessenger = null
        val connection = asrConnection
        asrConnection = null
        if (asrBound && connection != null) {
            runCatching { unbindService(connection) }
        }
        asrBound = false
    }

    private fun showDownloadProgress(message: String) {
        runOnUiThread {
            setStatus(message)
            progressValue = PERCENTAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
            updateModelChoiceViews()
            renderProgress()
        }
    }

    private fun renderProgress() {
        val view = progressView ?: return
        val visible = when (progressOwner) {
            ProgressOwner.TRANSCRIPTION -> busy && !downloadingModel
            ProgressOwner.NONE -> false
        }
        if (visible) {
            view.visibility = View.VISIBLE
            view.isIndeterminate = progressOwner == ProgressOwner.TRANSCRIPTION || progressValue == null
            if (!view.isIndeterminate && progressValue != null) view.progress = progressValue!!
        } else {
            view.visibility = View.GONE
        }
    }

    private fun setStatus(message: String) {
        statusMessage = message
        statusView?.text = message
        statusView?.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setBusyUi(value: Boolean) {
        busy = value
        updateModelChoiceViews()
        renderProgress()
    }

    private fun copyTranscript() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Trascrizione vocale", currentTranscript))
    }

    private fun shareTranscript() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentTranscript)
        }
        startActivity(Intent.createChooser(intent, "Condividi trascrizione"))
    }

    private fun eyebrow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(ACCENT_GOLD)
        typeface = consoleTypeface
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 27f
        letterSpacing = 0.02f
        setTextColor(TEXT_PRIMARY)
        typeface = orbitronTypeface
        setPadding(0, 0, 0, dp(12))
    }

    private fun bodyText(text: String, size: Float, strong: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(if (strong) TEXT_PRIMARY else TEXT_SECONDARY)
        if (strong) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(8))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.1f
        setTextColor(ACCENT_BLUE)
        typeface = consoleTypeface
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun consoleStrip(left: String, right: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = ConsoleFrameDrawable(SURFACE, ACCENT_BLUE, ACCENT_GOLD, resources.displayMetrics.density)
        val leftLabel = TextView(this@MainActivity).apply {
            text = left
            textSize = 11f
            letterSpacing = 0.08f
            typeface = consoleTypeface
            setTextColor(ACCENT_GOLD)
        }
        val rightLabel = TextView(this@MainActivity).apply {
            text = right
            textSize = 10f
            letterSpacing = 0.04f
            typeface = consoleTypeface
            gravity = Gravity.END
            setTextColor(TEXT_SECONDARY)
        }
        addView(leftLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(rightLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun transcriptionMetrics(): View {
        val metrics = panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(10))
        }
        fun cell(label: String, assign: (TextView) -> Unit): LinearLayout {
            val value = TextView(this).apply {
                text = "—"
                textSize = 16f
                typeface = orbitronTypeface
                setTextColor(TEXT_PRIMARY)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            assign(value)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    letterSpacing = 0.05f
                    typeface = consoleTypeface
                    gravity = Gravity.CENTER
                    setTextColor(ACCENT_GOLD)
                }, matchWrap())
                addView(value, matchWrap())
            }
        }
        metrics.addView(cell("AUDIO") { audioMetricValue = it }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(cell("PAROLE") { wordsMetricValue = it }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(cell("CONV.") { timeMetricValue = it }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        transcriptionMetricsPanel = metrics
        updateTranscriptionMetrics(currentTranscript)
        return metrics
    }

    private fun updateTranscriptionMetrics(transcript: String) {
        audioMetricValue?.text = formatDuration(audioDurationMs)
        wordsMetricValue?.text = transcript.trim().split(Regex("\\s+")).count { it.isNotBlank() }.toString()
        timeMetricValue?.text = formatDuration(conversionDurationMs)
        transcriptionMetricsPanel?.visibility = if (transcriptionStarted) View.VISIBLE else View.GONE
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = ((milliseconds.coerceAtLeast(0L) + 500L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(totalSeconds / 60L, seconds)
        }
    }

    private fun accentRule() = View(this).apply {
        setBackgroundColor(ACCENT_GOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
            bottomMargin = dp(14)
        }
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = ConsoleFrameDrawable(SURFACE, BORDER, ACCENT_BLUE, resources.displayMetrics.density)
    }

    private fun progressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
        progressTintList = ColorStateList.valueOf(ACCENT_GOLD)
        indeterminateTintList = ColorStateList.valueOf(ACCENT_BLUE)
    }

    private fun spaceScene(): View = object : View(this) {
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9DCEB.toInt() }
        private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6B85B.toInt(); style = Paint.Style.FILL }
        private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCEAF0.toInt(); style = Paint.Style.FILL }
        private val moonShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND; style = Paint.Style.FILL }
        private val moonRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_GOLD
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.4f
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C93A8.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val thinLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C93A8.toInt(); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 0.7f }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334FC4E0; style = Paint.Style.FILL }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_BLUE
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.2f
        }
        private val pIqaDPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_GOLD
            typeface = resources.getFont(R.font.klingon_piqad_hasta)
            textSize = dp(24).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(220))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // A crescent emits signal traces that resolve into the pIqaD word jatlh (“speak”).
            val moonX = w * .27f
            val moonY = h * .50f
            val moonRadius = dp(34).toFloat()
            canvas.drawCircle(moonX, moonY, moonRadius + dp(12), glowPaint)
            canvas.drawCircle(moonX, moonY, moonRadius, moonPaint)
            canvas.drawCircle(moonX - dp(15), moonY - dp(2), moonRadius * .84f, moonShadowPaint)
            canvas.drawArc(
                moonX - moonRadius,
                moonY - moonRadius,
                moonX + moonRadius,
                moonY + moonRadius,
                -62f,
                124f,
                false,
                moonRimPaint,
            )
            canvas.drawCircle(moonX + dp(21), moonY - dp(8), dp(2).toFloat(), thinLinePaint)
            canvas.drawCircle(moonX + dp(27), moonY + dp(10), dp(1).toFloat(), thinLinePaint)

            val waveStart = moonX + dp(27)
            val waveEnd = w * .60f
            for (row in -1..1) {
                val offset = dp(row * 9).toFloat()
                val trace = Path().apply {
                    moveTo(waveStart, moonY + offset)
                    cubicTo(
                        waveStart + dp(11),
                        moonY + offset - dp(10),
                        waveStart + dp(24),
                        moonY + offset - dp(10),
                        waveStart + dp(34),
                        moonY + offset,
                    )
                    cubicTo(
                        waveStart + dp(46),
                        moonY + offset + dp(10),
                        waveEnd - dp(13),
                        moonY + offset + dp(7),
                        waveEnd,
                        moonY + offset,
                    )
                }
                canvas.drawPath(trace, wavePaint)
            }
            canvas.drawCircle(waveEnd + dp(5), moonY, dp(2).toFloat(), signalPaint)
            canvas.drawText("\uF8D8\uF8D0\uF8E4", w * .70f, moonY + dp(9), pIqaDPaint)

            // Navigation console framing and signal marks.
            val bracket = Path().apply {
                moveTo(dp(8).toFloat(), dp(25).toFloat()); lineTo(dp(8).toFloat(), dp(8).toFloat()); lineTo(dp(42).toFloat(), dp(8).toFloat())
                moveTo(w - dp(42).toFloat(), dp(8).toFloat()); lineTo(w - dp(8).toFloat(), dp(8).toFloat()); lineTo(w - dp(8).toFloat(), dp(25).toFloat())
                moveTo(dp(8).toFloat(), h - dp(25).toFloat()); lineTo(dp(8).toFloat(), h - dp(8).toFloat()); lineTo(dp(42).toFloat(), h - dp(8).toFloat())
                moveTo(w - dp(42).toFloat(), h - dp(8).toFloat()); lineTo(w - dp(8).toFloat(), h - dp(8).toFloat()); lineTo(w - dp(8).toFloat(), h - dp(25).toFloat())
            }
            canvas.drawPath(bracket, linePaint)
            val stars = arrayOf(.08f to .19f, .18f to .78f, .31f to .21f, .48f to .82f, .56f to .17f, .91f to .19f, .68f to .88f)
            stars.forEachIndexed { index, p -> canvas.drawCircle(w * p.first, h * p.second, dp(if (index % 3 == 0) 2 else 1).toFloat(), starPaint) }
            canvas.drawRect(dp(16).toFloat(), h - dp(18), dp(50).toFloat(), h - dp(16), signalPaint)
            canvas.drawRect(w - dp(50), dp(16).toFloat(), w - dp(16), dp(18).toFloat(), linePaint)
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        letterSpacing = 0.06f
        typeface = consoleTypeface
        setTextColor(TEXT_PRIMARY)
        backgroundTintList = null
        background = ConsoleFrameDrawable(SURFACE, ACCENT_BLUE, ACCENT_GOLD, resources.displayMetrics.density)
        minHeight = dp(58)
        setOnClickListener { action() }
    }

    private fun setActionEnabled(button: Button?, enabled: Boolean) {
        button ?: return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun preferences() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun onDestroy() {
        mainHandler.removeCallbacks(revealCancelOptions)
        mainHandler.removeCallbacks(resetModelSelectionConfirmation)
        if (asrBound || asrProcessId > 0) releaseAsrService(killProcess = busy)
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val KEY_MODEL_INDEX = "model_index"
        const val NO_MODEL_INDEX = -1
        const val DISABLED_ALPHA = 0.45f
        const val CANCEL_AFTER_MS = 30_000L
        const val MODEL_CONFIRMATION_TIMEOUT_MS = 5_000L
        const val BACKGROUND = 0xFF061421.toInt()
        const val SURFACE = 0xFF102638.toInt()
        const val ACTIVE_SURFACE = 0xFF183A4D.toInt()
        const val BORDER = 0xFF31566B.toInt()
        const val TEXT_PRIMARY = 0xFFE0EDF1.toInt()
        const val TEXT_SECONDARY = 0xFFB8CDD5.toInt()
        const val ACCENT_GOLD = 0xFFE6B85B.toInt()
        const val BACK_NAV_ACCENT = 0xFF9B8B68.toInt()
        const val ACCENT_BLUE = 0xFF5DA4B4.toInt()
        const val ACCENT_CYAN = 0xFF75F4FF.toInt()
        val PERCENTAGE = Regex(": (\\d+)%")
    }
}
