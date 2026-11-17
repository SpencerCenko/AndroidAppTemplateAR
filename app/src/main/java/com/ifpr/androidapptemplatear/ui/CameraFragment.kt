package com.ifpr.androidapptemplatear.ui

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.widget.ImageView
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.ifpr.androidapptemplatear.R
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class CameraFragment : Fragment() {

    private enum class Mode {
        CHECKING,
        AR,
        CAMERA,
        STATIC
    }

    companion object {
        private const val AVAILABILITY_TIMEOUT_MS = 4000L
        private const val AR_TIMEOUT_MS = 15000L
        private const val CAMERA_TIMEOUT_MS = 8000L

        // ---- Batalha estilo osu! (ajuste a dificuldade aqui) ----
        private const val MONSTER_MAX_HP = 30
        private const val PLAYER_MAX_HP = 5

        // Vidas da partida, compartilhadas por TODOS os inimigos
        // (ficam no GameViewModel e não resetam ao trocar de inimigo)
        private const val PLAYER_LIVES = GameViewModel.MAX_LIVES

        // Dano por acerto = dano do julgamento + (combo / COMBO_STEP)
        private const val COMBO_STEP = 10

        // Dano que o jogador toma a cada erro
        private const val MONSTER_DAMAGE = 1

        // Quantos círculos numerados por sequência
        private const val PATTERN_MIN = 3
        private const val PATTERN_MAX = 5
        private const val PATTERN_GAP_MS = 500L

        // Distância mínima (em dp) entre os centros dos círculos na tela
        private const val MIN_CIRCLE_GAP_DP = 150

        // Distância do próximo círculo da sequência em relação ao anterior:
        // entre NEXT_CIRCLE_MIN_DP e NEXT_CIRCLE_MIN_DP + NEXT_CIRCLE_VAR_DP
        private const val NEXT_CIRCLE_MIN_DP = 150
        private const val NEXT_CIRCLE_VAR_DP = 110

        // Tempo do anel (aproximação) e intervalo entre os círculos.
        // Ambos diminuem conforme o combo cresce.
        private const val APPROACH_MS = 900L
        private const val APPROACH_MIN_MS = 600L
        private const val BEAT_MS = 450L
        private const val BEAT_MIN_MS = 280L

        // Janelas de precisão (em ms antes/depois do momento perfeito)
        private const val PERFECT_MS = 70L
        private const val GREAT_MS = 140L
        private const val OK_MS = 220L

        // Toque mais cedo que isso é ignorado; entre isso e OK_MS é erro
        private const val EARLY_IGNORE_MS = 380L

        // Tocar fora de um círculo conta como erro (no osu! não conta)
        private const val PENALIZE_EMPTY_TAPS = false

        private val J_PERFECT = Judgement("PERFEITO!", Color.rgb(80, 220, 255), 3, 300)
        private val J_GREAT = Judgement("ÓTIMO", Color.rgb(120, 230, 120), 2, 100)
        private val J_OK = Judgement("OK", Color.rgb(255, 210, 80), 1, 50)
    }

    private val localizacaoController = LocalizacaoController(this)

    private var mode = Mode.CHECKING

    private var container: FrameLayout? = null
    private var placeButton: HorrorButton? = null
    private var statusText: TextView? = null
    private var hostView: View? = null

    // Interface (HUD) em janelas próprias, acima do SceneView
    private var hudTopView: View? = null
    private var hudBottomView: View? = null
    private var hudTopDialog: Dialog? = null
    private var hudBottomDialog: Dialog? = null
    private var hudBottomHeight = 0
    private var killsText: TextView? = null
    private var barsGroup: View? = null

    // Batalha
    private var battleButton: HorrorButton? = null
    private var battleLayer: FrameLayout? = null
    private var battleDialog: Dialog? = null
    private var gameOverDialog: Dialog? = null
    private var livesText: TextView? = null
    private var comboText: TextView? = null
    private var scoreText: TextView? = null
    private var linesView: LinkLinesView? = null
    private var hpBar: HpBarView? = null
    private var playerHpBar: HpBarView? = null

    private var battleActive = false
    private var monsterHp = 0
    private var playerHp = 0
    private var combo = 0
    private var maxCombo = 0
    private var score = 0
    private var patternCounter = 0
    private val activeCircles = mutableListOf<TargetCircle>()

    // Vidas e monstros derrotados ficam no GameViewModel (da Activity),
    // então sobrevivem à troca de inimigo e de aba.
    private lateinit var gameViewModel: GameViewModel

    private var lives: Int
        get() = gameViewModel.lives
        set(value) {
            gameViewModel.lives = value
        }

    private var monstersDefeated: Int
        get() = gameViewModel.monstersDefeated
        set(value) {
            gameViewModel.monstersDefeated = value
        }
    private var monsterBaseScale: Scale? = null

    private var hitJob: Job? = null
    private var battleJob: Job? = null
    private var spawnJob: Job? = null

    private var sceneView: SceneView? = null
    private var arView: ARSceneView? = null

    private var modelNode: ModelNode? = null
    private var anchorNode: AnchorNode? = null
    private var placementHit: HitResult? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null

    private var loadJob: Job? = null
    private var availabilityJob: Job? = null
    private var arWatchdogJob: Job? = null
    private var cameraWatchdogJob: Job? = null

    private var generation = 0
    private var modelGeneration = 0
    private var pendingAr = false

    private var arTracking = false
    private var cameraStreaming = false

    private var currentModelFile = "neutrofilo.glb"

    private lateinit var sharedViewModel: SharedModelViewModel

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (container != null) {
                when {
                    !granted -> {
                        Toast.makeText(
                            requireContext(),
                            "Permissão de câmera negada",
                            Toast.LENGTH_LONG
                        ).show()

                        startPreview(useCamera = false)
                    }

                    pendingAr -> startAr()

                    else -> startPreview(useCamera = true)
                }
            }
        }

    private var musicaPlayer: MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_camera,
            parent,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        gameViewModel =
            ViewModelProvider(requireActivity())[
                GameViewModel::class.java
            ]

        container = view.findViewById(R.id.sceneContainer)

        createControls()

        sharedViewModel =
            ViewModelProvider(requireActivity())[
                SharedModelViewModel::class.java
            ]

        val selectedModel = sharedViewModel.selectedModel.value

        if (!selectedModel.isNullOrBlank()) {
            currentModelFile = selectedModel
        } else {
            sharedViewModel.selectModel(currentModelFile)
        }

        sharedViewModel.selectedModel.observe(viewLifecycleOwner) { path ->
            if (!path.isNullOrBlank() && path != currentModelFile) {
                currentModelFile = path
                loadModel()
            }
        }

        placeButton?.setOnClickListener {
            when (mode) {
                Mode.AR -> placeModel()

                Mode.CAMERA,
                Mode.STATIC -> {
                    startPreview(useCamera = mode == Mode.CAMERA)
                }

                Mode.CHECKING -> Unit
            }
        }

        iniciarMusica()
        checkAvailability()

        val parent = container?.parent as? ViewGroup

        if (parent != null) {
            localizacaoController.conectar(parent) { arquivo ->
                sharedViewModel.selectModel(arquivo)
            }

            placeButton?.bringToFront()
        }
    }

    override fun onResume() {
        super.onResume()
        view?.post { refreshHud() }
    }

    override fun onPause() {
        // Evita que os círculos expirem (e tirem vida) com o app em segundo plano.
        if (battleActive) {
            endBattle("Batalha pausada.")
        }
        hideHud()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)

        if (hidden) {
            if (battleActive) endBattle("Batalha pausada.")
            hideHud()
            runCatching { gameOverDialog?.dismiss() }
            gameOverDialog = null
        } else {
            view?.post { refreshHud() }
        }
    }

    /** Mostra o HUD (ou o Game Over, se as vidas acabaram). */
    private fun refreshHud() {
        if (container == null || isHidden) return

        if (lives <= 0) {
            showGameOver()
        } else {
            showHud()
        }
    }

    private fun createControls() {
        val parent = container?.parent as? ViewGroup ?: return
        val ctx = requireContext()
        val bone = Color.rgb(240, 232, 228)

        hostView = parent

        // O SceneView é desenhado por cima de qualquer View da tela, então a
        // interface vive em janelas próprias (HUD) e os controles do XML saem.
        requireView().findViewById<View>(R.id.place)?.visibility = View.GONE

        val titleView =
            requireView().findViewById<View>(R.id.titleImage) as? ImageView
        val titleDrawable =
            titleView?.drawable?.constantState?.newDrawable()?.mutate()

        if (titleDrawable != null) {
            titleView?.visibility = View.GONE
        }

        // ---------- Camada dos círculos (janela própria, veja showBattleDialog) ----------
        // Tocar nela (fora de um círculo) conta como erro, se configurado.
        battleLayer = FrameLayout(ctx).apply {
            setOnTouchListener { _, event ->
                if (
                    event.actionMasked == MotionEvent.ACTION_DOWN &&
                    battleActive &&
                    PENALIZE_EMPTY_TAPS
                ) {
                    registerMiss()
                }
                true
            }
        }

        // Linhas pontilhadas ligando os círculos numerados (por baixo deles)
        linesView = LinkLinesView(ctx) { activeCircles }

        battleLayer?.addView(
            linesView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        comboText = TextView(ctx).apply {
            visibility = View.GONE
            textSize = 28f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            setTextColor(bone)
            setShadowLayer(16f, 0f, 0f, Color.rgb(210, 0, 25))
        }

        battleLayer?.addView(
            comboText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(100)
                rightMargin = dp(16)
            }
        )

        scoreText = TextView(ctx).apply {
            visibility = View.GONE
            textSize = 16f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.1f
            setTextColor(bone)
            setShadowLayer(10f, 0f, 0f, Color.BLACK)
        }

        battleLayer?.addView(
            scoreText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(104)
                leftMargin = dp(16)
            }
        )

        // ---------- HUD superior: vidas, título e fantasmas banidos ----------
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        livesText = chip(ctx, bone)
        killsText = chip(ctx, bone)

        topBar.addView(
            livesText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        if (titleDrawable != null) {
            topBar.addView(
                ImageView(ctx).apply {
                    setImageDrawable(titleDrawable)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                }
            )
        } else {
            topBar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        }

        topBar.addView(
            killsText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ---------- HUD inferior: status, barras de vida e botões ----------
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(228, 9, 9, 13))
                setStroke(dp(1), Color.argb(130, 193, 18, 31))
            }
        }

        statusText = TextView(ctx).apply {
            text = "Preparando visualização…"
            textSize = 14f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setTextColor(Color.rgb(226, 219, 215))
            setLines(2)
            ellipsize = TextUtils.TruncateAt.END
        }

        card.addView(statusText)

        hpBar = HpBarView(
            ctx,
            Color.rgb(120, 0, 16),
            Color.rgb(235, 20, 40)
        ).apply { max = MONSTER_MAX_HP }

        playerHpBar = HpBarView(
            ctx,
            Color.rgb(24, 96, 70),
            Color.rgb(120, 245, 175)
        ).apply { max = PLAYER_MAX_HP }

        val bars = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE // INVISIBLE mantém a altura do painel fixa
        }

        bars.addView(barRow(ctx, "FANTASMA", hpBar!!))
        bars.addView(
            barRow(ctx, "VOCÊ", playerHpBar!!),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        barsGroup = bars

        card.addView(
            bars,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        bottom.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        placeButton = HorrorButton(ctx, primary = false).apply {
            text = "Aguarde"
            isEnabled = false
        }

        battleButton = HorrorButton(ctx, primary = true).apply {
            text = "Batalhar"
            isEnabled = false
            setOnClickListener {
                if (battleActive) {
                    endBattle("Você fugiu… por enquanto.")
                } else {
                    startBattle()
                }
            }
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        buttons.addView(placeButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        buttons.addView(
            battleButton,
            LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                leftMargin = dp(10)
            }
        )

        bottom.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        hudTopView = topBar
        hudBottomView = bottom

        updateLivesUi()
    }

    /** Cápsula escura com borda vermelha (vidas, fantasmas banidos). */
    private fun chip(ctx: Context, textColor: Int): TextView {
        return TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(textColor)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(220, 9, 9, 13))
                setStroke(dp(1), Color.argb(130, 193, 18, 31))
            }
        }
    }

    private fun barRow(ctx: Context, label: String, bar: HpBarView): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            TextView(ctx).apply {
                text = label
                textSize = 10f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                letterSpacing = 0.18f
                setTextColor(Color.rgb(150, 140, 140))
            },
            LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        row.addView(bar, LinearLayout.LayoutParams(0, dp(9), 1f))

        return row
    }

    // ------------------------------------------------------------------
    // HUD em janelas próprias (acima do SceneView)
    // ------------------------------------------------------------------

    private fun newHudDialog(content: View): Dialog {
        (content.parent as? ViewGroup)?.removeView(content)

        val dialog = Dialog(
            requireContext(),
            android.R.style.Theme_Translucent_NoTitleBar
        ).apply {
            setContentView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setCancelable(false)
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }

        return dialog
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /**
     * Posiciona a janela no topo ou na base da área da câmera, com o
     * tamanho exato do conteúdo (o resto da tela continua tocável).
     * Retorna a altura da janela.
     */
    private fun layoutHudWindow(
        dialog: Dialog,
        content: View,
        atTop: Boolean
    ): Int {
        val host = hostView ?: return 0

        val loc = IntArray(2)
        host.getLocationOnScreen(loc)

        if (atTop) {
            // Evita ficar sob a barra de status.
            val extra = (statusBarHeight() - loc[1]).coerceAtLeast(0)
            content.setPadding(dp(12), dp(8) + extra, dp(12), dp(8))
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val height = content.measuredHeight

        dialog.window?.let { window ->
            val params = window.attributes
            params.gravity = Gravity.TOP or Gravity.START
            params.x = loc[0]
            params.y = if (atTop) loc[1] else loc[1] + host.height - height
            params.width = host.width
            params.height = height
            window.attributes = params
        }

        return height
    }

    private fun showHud() {
        val host = hostView ?: return
        if (container == null || lives <= 0) return

        // Ainda sem medidas: tenta de novo no próximo quadro.
        if (host.width == 0 || host.height == 0) {
            host.post { if (container != null) showHud() }
            return
        }

        val top = hudTopView ?: return
        val bottom = hudBottomView ?: return

        if (hudTopDialog == null) hudTopDialog = newHudDialog(top)
        if (hudBottomDialog == null) hudBottomDialog = newHudDialog(bottom)

        hudTopDialog?.let { layoutHudWindow(it, top, atTop = true) }
        hudBottomDialog?.let {
            hudBottomHeight = layoutHudWindow(it, bottom, atTop = false)
        }

        runCatching { hudTopDialog?.show() }
        runCatching { hudBottomDialog?.show() }
    }

    private fun hideHud() {
        runCatching { hudTopDialog?.hide() }
        runCatching { hudBottomDialog?.hide() }
    }

    private fun destroyHud() {
        runCatching { hudTopDialog?.dismiss() }
        runCatching { hudBottomDialog?.dismiss() }
        hudTopDialog = null
        hudBottomDialog = null
        hudTopView = null
        hudBottomView = null
    }

    /**
     * Identificação por características comuns de emuladores.
     * Por escolha deste aplicativo, eles iniciam sem ARCore.
     */
    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains(
                    "Android SDK built for",
                    ignoreCase = true
                ) ||
                Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    private fun checkAvailability() {
        availabilityJob?.cancel()

        // Não tenta iniciar ARCore no emulador.
        if (isEmulator()) {
            requestMode(useAr = false)
            return
        }

        val expected = generation
        var resolved = false

        availabilityJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AVAILABILITY_TIMEOUT_MS)

            if (
                !resolved &&
                container != null &&
                expected == generation
            ) {
                resolved = true
                requestMode(useAr = false)
            }
        }

        try {
            ArCoreApk.getInstance()
                .checkAvailabilityAsync(requireContext()) { availability ->
                    activity?.runOnUiThread {
                        if (
                            !resolved &&
                            container != null &&
                            expected == generation
                        ) {
                            resolved = true
                            availabilityJob?.cancel()
                            availabilityJob = null

                            val installed =
                                availability ==
                                        ArCoreApk.Availability.SUPPORTED_INSTALLED

                            requestMode(useAr = installed)
                        }
                    }
                }
        } catch (_: Exception) {
            if (
                !resolved &&
                container != null &&
                expected == generation
            ) {
                resolved = true
                availabilityJob?.cancel()
                availabilityJob = null

                requestMode(useAr = false)
            }
        }
    }

    private fun requestMode(useAr: Boolean) {
        pendingAr = useAr

        val permitted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !permitted -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }

            useAr -> startAr()

            else -> startPreview(useCamera = true)
        }
    }

    private fun startAr() {
        val host = container ?: return

        clearScene()
        mode = Mode.AR

        val expected = generation

        statusText?.text = "Aponte para uma mesa ou para o chão."

        placeButton?.apply {
            text = "Posicionar"
            isEnabled = false
        }

        try {
            val ar = ARSceneView(requireContext())

            arView = ar
            sceneView = ar

            ar.configureSession { _, config ->
                config.planeFindingMode =
                    Config.PlaneFindingMode.HORIZONTAL

                config.lightEstimationMode =
                    Config.LightEstimationMode.DISABLED
            }

            ar.onSessionFailed = {
                host.post {
                    if (
                        container != null &&
                        expected == generation &&
                        mode == Mode.AR
                    ) {
                        startPreview(useCamera = true)
                    }
                }
            }

            ar.onSessionUpdated = { _, frame ->
                if (
                    expected == generation &&
                    mode == Mode.AR
                ) {
                    arTracking =
                        frame.camera.trackingState ==
                                TrackingState.TRACKING

                    if (anchorNode == null) {
                        placementHit =
                            if (
                                arTracking &&
                                ar.width > 0 &&
                                ar.height > 0
                            ) {
                                frame.hitTest(
                                    ar.width / 2f,
                                    ar.height / 2f
                                ).firstOrNull { hit ->
                                    val plane = hit.trackable as? Plane

                                    plane != null &&
                                            plane.type ==
                                            Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                            plane.isPoseInPolygon(hit.hitPose)
                                }
                            } else {
                                null
                            }

                        val ready =
                            placementHit != null &&
                                    modelNode != null

                        placeButton?.isEnabled = ready

                        statusText?.text =
                            if (ready) {
                                "Superfície encontrada. Toque em Posicionar."
                            } else {
                                "Mova o aparelho devagar sobre uma superfície."
                            }
                    }
                }
            }

            ar.lifecycle = viewLifecycleOwner.lifecycle

            host.addView(ar, fullSize())

            startArWatchdog(expected)
            loadModel()

        } catch (_: Exception) {
            startPreview(useCamera = true)
        }
    }

    /**
     * Troca automaticamente quando o ARCore não consegue rastrear.
     * Conta somente o tempo em que a tela está em primeiro plano.
     * A ausência de uma superfície, sozinha, não provoca a troca.
     */
    private fun startArWatchdog(expected: Int) {
        arWatchdogJob?.cancel()

        arWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var withoutTrackingMs = 0L
            var lastTime = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(500L)

                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.AR
                ) {
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTime
                lastTime = now

                val resumed = viewLifecycleOwner.lifecycle
                    .currentState.isAtLeast(Lifecycle.State.RESUMED)

                if (!resumed || arTracking) {
                    withoutTrackingMs = 0L
                } else {
                    withoutTrackingMs += elapsed
                }

                if (withoutTrackingMs >= AR_TIMEOUT_MS) {
                    startPreview(useCamera = true)
                    return@launch
                }
            }
        }
    }

    private fun startPreview(useCamera: Boolean) {
        val host = container ?: return

        clearScene()

        mode = if (useCamera) Mode.CAMERA else Mode.STATIC

        placeButton?.apply {
            text = "Centralizar"
            isEnabled = false
        }

        statusText?.text = if (useCamera) {
            "Modelo sobre a câmera, sem fixação no ambiente."
        } else {
            "Visualização 3D sem câmera."
        }

        host.setBackgroundColor(Color.rgb(45, 55, 65))

        val cameraView = if (useCamera) {
            PreviewView(requireContext()).apply {
                implementationMode =
                    PreviewView.ImplementationMode.COMPATIBLE

                scaleType = PreviewView.ScaleType.FILL_CENTER

                host.addView(this, fullSize())
            }
        } else {
            null
        }

        try {
            val scene = SceneView(
                context = requireContext(),
                isOpaque = !useCamera
            )

            scene.lifecycle = viewLifecycleOwner.lifecycle
            scene.cameraNode.position = Position(0f, 0f, 3f)
            scene.cameraNode.lookAt(Position(0f, 0f, 0f))

            sceneView = scene

            host.addView(scene, fullSize())

            loadModel()

            if (cameraView != null) {
                openCamera(cameraView)
            }
        } catch (_: Exception) {
            statusText?.text = "Não foi possível iniciar a cena 3D."
        }
    }

    private fun openCamera(previewView: PreviewView) {
        val context = context ?: return

        val expected = generation
        val owner = viewLifecycleOwner

        // O prazo também cobre a obtenção do CameraProvider.
        startCameraWatchdog(expected)

        try {
            val future = ProcessCameraProvider.getInstance(context)

            future.addListener({
                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.CAMERA
                ) {
                    return@addListener
                }

                try {
                    val provider = future.get()

                    val selector = when {
                        provider.hasCamera(
                            CameraSelector.DEFAULT_BACK_CAMERA
                        ) -> CameraSelector.DEFAULT_BACK_CAMERA

                        provider.hasCamera(
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        ) -> CameraSelector.DEFAULT_FRONT_CAMERA

                        else -> {
                            startPreview(useCamera = false)
                            return@addListener
                        }
                    }

                    val preview = Preview.Builder().build()

                    preview.setSurfaceProvider(
                        previewView.surfaceProvider
                    )

                    cameraProvider = provider
                    cameraPreview = preview

                    previewView.previewStreamState.observe(owner) { state ->
                        if (
                            expected == generation &&
                            mode == Mode.CAMERA
                        ) {
                            cameraStreaming =
                                state == PreviewView.StreamState.STREAMING
                        }
                    }

                    provider.bindToLifecycle(
                        owner,
                        selector,
                        preview
                    )

                } catch (_: Exception) {
                    if (
                        expected == generation &&
                        container != null
                    ) {
                        startPreview(useCamera = false)
                    }
                }
            }, ContextCompat.getMainExecutor(context))

        } catch (_: Exception) {
            if (
                expected == generation &&
                container != null
            ) {
                startPreview(useCamera = false)
            }
        }
    }

    private fun startCameraWatchdog(expected: Int) {
        cameraWatchdogJob?.cancel()

        cameraWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var withoutCameraMs = 0L
            var lastTime = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(500L)

                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.CAMERA
                ) {
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTime
                lastTime = now

                val resumed = viewLifecycleOwner.lifecycle
                    .currentState.isAtLeast(Lifecycle.State.RESUMED)

                if (!resumed || cameraStreaming) {
                    withoutCameraMs = 0L
                } else {
                    withoutCameraMs += elapsed
                }

                if (withoutCameraMs >= CAMERA_TIMEOUT_MS) {
                    startPreview(useCamera = false)
                    return@launch
                }
            }
        }
    }

    private fun loadModel() {
        val scene = sceneView ?: return
        val location = modelLocation(currentModelFile)

        if (location == null) {
            statusText?.text =
                "Modelo não encontrado: $currentModelFile"

            placeButton?.isEnabled = false
            return
        }

        loadJob?.cancel()
        removeModel()

        val expected = generation
        val expectedModel = ++modelGeneration
        val isAr = mode == Mode.AR
        val fileName = currentModelFile

        placeButton?.isEnabled = false

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val instance =
                    scene.modelLoader.loadModelInstance(location)

                if (
                    expected != generation ||
                    expectedModel != modelGeneration ||
                    sceneView !== scene
                ) {
                    return@launch
                }

                if (instance == null) {
                    statusText?.text = "Não foi possível abrir o modelo."
                    return@launch
                }

                val node = ModelNode(
                    modelInstance = instance,
                    scaleToUnits = if (isAr) 0.5f else 1.5f,
                    centerOrigin = Position(0f, 0f, 0f)
                )

                // Mantido da branch original: move/gira, mas não escala.
                node.isEditable = true
                node.isScaleEditable = false
                modelNode = node

                if (!isAr) {
                    scene.addChildNode(node)
                    placeButton?.isEnabled = true
                }

                updateBattleUi()

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (
                    expected == generation &&
                    expectedModel == modelGeneration
                ) {
                    statusText?.text =
                        "Erro ao carregar: $fileName"
                }
            }
        }
    }

    private fun iniciarMusica() {
        musicaPlayer?.release()
        musicaPlayer = MediaPlayer.create(requireContext(), R.raw.musica)

        musicaPlayer?.isLooping = true
        musicaPlayer?.start()
    }

    private fun placeModel() {
        val ar = arView ?: return

        if (anchorNode != null) {
            loadModel()
            return
        }

        val hit = placementHit ?: return
        val model = modelNode ?: return

        try {
            val anchor = AnchorNode(
                engine = ar.engine,
                anchor = hit.createAnchor()
            )

            anchor.addChildNode(model)
            ar.addChildNode(anchor)

            anchorNode = anchor
            placementHit = null

            placeButton?.apply {
                text = "Reposicionar"
                isEnabled = true
            }

            statusText?.text = "Objeto posicionado no ambiente."

            updateBattleUi()

        } catch (_: Exception) {
            statusText?.text =
                "Não foi possível posicionar. Tente outra superfície."
        }
    }

    // ------------------------------------------------------------------
    // Sistema de batalha com combos
    // ------------------------------------------------------------------

    private fun canBattle(): Boolean {
        return mode != Mode.CHECKING &&
                lives > 0 &&
                modelNode != null &&
                (mode != Mode.AR || anchorNode != null) &&
                battleJob?.isActive != true
    }

    private fun updateBattleUi() {
        battleButton?.apply {
            text = if (battleActive) "Fugir" else "Batalhar"
            setPrimary(!battleActive)
            isEnabled = battleActive || canBattle()
        }

        barsGroup?.visibility =
            if (battleActive) View.VISIBLE else View.INVISIBLE
    }

    private fun updateComboText() {
        comboText?.apply {
            if (battleActive && combo >= 2) {
                text = "COMBO x$combo"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun updateScoreText() {
        scoreText?.apply {
            text = "Pontos: $score"
            visibility = if (battleActive) View.VISIBLE else View.GONE
        }
    }

    private fun startBattle() {
        val node = modelNode ?: return
        if (!canBattle()) return

        battleActive = true
        monsterHp = MONSTER_MAX_HP
        playerHp = PLAYER_MAX_HP
        combo = 0
        maxCombo = 0
        score = 0
        patternCounter = 0
        monsterBaseScale = node.scale

        // Durante a batalha o dedo ataca pelos círculos, não move o modelo.
        node.isEditable = false

        placeButton?.isEnabled = false

        hpBar?.progress = monsterHp
        playerHpBar?.progress = playerHp

        showBattleDialog()

        statusText?.text =
            "Ele está aqui. Toque nos círculos em ordem, quando o anel fechar."

        updateComboText()
        updateScoreText()
        updateBattleUi()
        startSpawning()
    }

    /** Gera sequências de círculos numerados, uma após a outra. */
    private fun startSpawning() {
        spawnJob?.cancel()

        spawnJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(800L)

            while (isActive && battleActive) {
                spawnPattern(Random.nextInt(PATTERN_MIN, PATTERN_MAX + 1))
                delay(PATTERN_GAP_MS)
            }
        }
    }

    private suspend fun spawnPattern(count: Int) {
        val layer = battleLayer ?: return

        if (layer.width == 0 || layer.height == 0) {
            delay(200L)
            return
        }

        val radius = dp(32).toFloat()
        val half = radius * 2.5f // metade do lado da View (anel no tamanho máximo)

        // A janela de combate já termina acima do painel/botões;
        // só evitamos a área do título (topo).
        val minX = half
        val maxX = layer.width - half
        val minY = dp(110) + half
        val maxY = layer.height - half - dp(8)

        if (maxX <= minX || maxY <= minY) {
            delay(500L)
            return
        }

        val approach = (APPROACH_MS - combo * 10L)
            .coerceAtLeast(APPROACH_MIN_MS)

        val beat = (BEAT_MS - combo * 8L)
            .coerceAtLeast(BEAT_MIN_MS)

        val patternId = ++patternCounter

        var previous: Pair<Float, Float>? = null

        for (number in 1..count) {
            if (!battleActive) return

            val (x, y) = pickPosition(previous, minX, maxX, minY, maxY)

            spawnCircle(patternId, number, x, y, radius, approach)
            previous = Pair(x, y)

            delay(beat)
        }
    }

    /**
     * Escolhe uma posição afastada de todos os círculos que ainda estão
     * na tela. Tenta várias vezes; se a tela estiver cheia, usa a
     * posição mais afastada encontrada.
     */
    private fun pickPosition(
        previous: Pair<Float, Float>?,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float
    ): Pair<Float, Float> {
        val minGap = dp(MIN_CIRCLE_GAP_DP).toFloat()
        val others = activeCircles.map { Pair(it.cx, it.cy) }

        var best = Pair((minX + maxX) / 2f, (minY + maxY) / 2f)
        var bestGap = -1f

        for (attempt in 0 until 40) {
            val candidate = if (previous == null) {
                Pair(
                    minX + Random.nextFloat() * (maxX - minX),
                    minY + Random.nextFloat() * (maxY - minY)
                )
            } else {
                val angle = Random.nextDouble(0.0, 2 * PI)
                val distance = (
                        dp(NEXT_CIRCLE_MIN_DP) +
                                Random.nextInt(dp(NEXT_CIRCLE_VAR_DP))
                        ).toFloat()

                Pair(
                    (previous.first + cos(angle).toFloat() * distance)
                        .coerceIn(minX, maxX),
                    (previous.second + sin(angle).toFloat() * distance)
                        .coerceIn(minY, maxY)
                )
            }

            val gap = others.minOfOrNull { other ->
                val dx = other.first - candidate.first
                val dy = other.second - candidate.second
                sqrt(dx * dx + dy * dy)
            } ?: Float.MAX_VALUE

            if (gap >= minGap) return candidate

            if (gap > bestGap) {
                bestGap = gap
                best = candidate
            }
        }

        return best
    }

    private fun spawnCircle(
        patternId: Int,
        number: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        approachMs: Long
    ) {
        val layer = battleLayer ?: return
        if (!battleActive) return

        val size = (radius * 5f).toInt()

        val circle = TargetCircle(
            context = requireContext(),
            number = number,
            patternId = patternId,
            cx = cx,
            cy = cy,
            radiusPx = radius,
            approachMs = approachMs,
            lateMs = OK_MS,
            // Só o primeiro da fila pode ser acertado (ordem 1, 2, 3…)
            canHit = { activeCircles.firstOrNull() === it },
            onTap = { c, errorMs -> onCircleTap(c, errorMs) },
            onExpire = { c -> onCircleExpired(c) }
        )

        layer.addView(
            circle,
            FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (cx - size / 2f).toInt()
                topMargin = (cy - size / 2f).toInt()
            }
        )

        activeCircles.add(circle)
        linesView?.invalidate()

        circle.start()
    }

    private fun removeCircle(circle: TargetCircle) {
        circle.dismiss()
        activeCircles.remove(circle)
        battleLayer?.removeView(circle)
        linesView?.invalidate()
    }

    /**
     * errorMs < 0: toque antes do momento perfeito (anel ainda fechando).
     * errorMs > 0: toque depois.
     */
    private fun onCircleTap(circle: TargetCircle, errorMs: Long) {
        if (!battleActive) return

        // Cedo demais: ignora, o círculo continua lá.
        if (errorMs < -EARLY_IGNORE_MS) return

        // Cedo, mas perto o bastante para valer como erro.
        if (errorMs < -OK_MS) {
            removeCircle(circle)
            showJudgement("CEDO!", Color.rgb(255, 120, 80), circle.cx, circle.cy)
            registerMiss()
            return
        }

        val judgement = when {
            abs(errorMs) <= PERFECT_MS -> J_PERFECT
            abs(errorMs) <= GREAT_MS -> J_GREAT
            else -> J_OK
        }

        removeCircle(circle)

        combo++
        if (combo > maxCombo) maxCombo = combo

        score += judgement.points * (1 + combo / 10)

        showJudgement(judgement.label, judgement.color, circle.cx, circle.cy)

        updateComboText()
        updateScoreText()

        hitMonster(judgement.damage + combo / COMBO_STEP)
    }

    /** O anel fechou e o jogador não tocou. */
    private fun onCircleExpired(circle: TargetCircle) {
        removeCircle(circle)
        if (!battleActive) return

        showJudgement("ERROU", Color.rgb(255, 70, 70), circle.cx, circle.cy)
        registerMiss()
    }

    /** Texto flutuante (PERFEITO!, ÓTIMO, OK, ERROU…) sobre o círculo. */
    private fun showJudgement(text: String, color: Int, x: Float, y: Float) {
        val layer = battleLayer ?: return

        val label = TextView(requireContext()).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        layer.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (x - dp(40)).toInt().coerceAtLeast(0)
                topMargin = (y - dp(70)).toInt().coerceAtLeast(0)
            }
        )

        label.animate()
            .alpha(0f)
            .translationYBy(-dp(28).toFloat())
            .setDuration(650L)
            .withEndAction { layer.removeView(label) }
            .start()
    }

    /** O jogador errou: zera o combo e o monstro ataca. */
    private fun registerMiss() {
        if (!battleActive) return

        combo = 0
        updateComboText()

        playerHp = (playerHp - MONSTER_DAMAGE).coerceAtLeast(0)
        playerHpBar?.progress = playerHp

        statusText?.text =
            "O fantasma atacou! Sua alma: $playerHp / $PLAYER_MAX_HP"

        monsterAttackEffect()

        if (playerHp == 0) {
            loseLife()
        }
    }

    /** A vida do jogador na batalha acabou: perde 1 das 3 vidas. */
    private fun loseLife() {
        lives = (lives - 1).coerceAtLeast(0)
        updateLivesUi()

        if (lives == 0) {
            endBattle("Game over!")
            showGameOver()
        } else {
            val restantes = if (lives == 1) "1 vida" else "$lives vidas"
            endBattle(
                "O fantasma te alcançou… Restam $restantes. " +
                        "Toque em Batalhar para tentar de novo."
            )
        }
    }

    private fun updateLivesUi() {
        val hearts = SpannableStringBuilder()

        for (i in 0 until PLAYER_LIVES) {
            val start = hearts.length
            hearts.append("♥")
            hearts.setSpan(
                ForegroundColorSpan(
                    if (i < lives) Color.rgb(225, 25, 45)
                    else Color.argb(90, 255, 255, 255)
                ),
                start,
                hearts.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (i < PLAYER_LIVES - 1) hearts.append(" ")
        }

        livesText?.text = hearts
        killsText?.text = "☠ $monstersDefeated"
    }

    /** Tela de Game Over (em janela própria, acima de tudo). */
    private fun showGameOver() {
        if (gameOverDialog?.isShowing == true) return

        hideHud()

        val ctx = requireContext()
        val bone = Color.rgb(240, 232, 228)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = resources.displayMetrics.heightPixels * 0.75f
                setGradientCenter(0.5f, 0.42f)
                colors = intArrayOf(Color.rgb(78, 0, 10), Color.rgb(4, 0, 2))
            }
            isClickable = true // bloqueia toques no que está atrás
        }

        content.addView(
            TextView(ctx).apply {
                text = "GAME OVER"
                textSize = 46f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.12f
                setTextColor(Color.rgb(215, 25, 45))
                setShadowLayer(28f, 0f, 0f, Color.rgb(255, 0, 30))
            }
        )

        content.addView(
            View(ctx).apply {
                setBackgroundColor(Color.argb(170, 193, 18, 31))
            },
            LinearLayout.LayoutParams(dp(120), dp(2)).apply {
                topMargin = dp(16)
            }
        )

        content.addView(
            TextView(ctx).apply {
                text = "O fantasma levou todas as suas vidas.\n\n" +
                        "Fantasmas banidos: $monstersDefeated"
                textSize = 17f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                setTextColor(bone)
                alpha = 0.9f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        )

        content.addView(
            HorrorButton(ctx, primary = true).apply {
                text = "Tentar de novo"
                setOnClickListener { restartGame() }
            },
            LinearLayout.LayoutParams(dp(230), dp(54)).apply {
                topMargin = dp(36)
            }
        )

        val dialog = Dialog(
            ctx,
            android.R.style.Theme_Translucent_NoTitleBar
        ).apply {
            setContentView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setCancelable(false)
        }

        gameOverDialog = dialog

        runCatching { dialog.show() }
    }

    private fun restartGame() {
        runCatching { gameOverDialog?.dismiss() }
        gameOverDialog = null

        lives = PLAYER_LIVES
        monstersDefeated = 0
        updateLivesUi()

        statusText?.text = "Você voltou… Toque em Batalhar."
        updateBattleUi()
        showHud()
    }

    private fun hitMonster(damage: Int) {
        if (!battleActive) return
        val node = modelNode ?: return

        monsterHp = (monsterHp - damage).coerceAtLeast(0)
        hpBar?.progress = monsterHp
        statusText?.text =
            "Acertou! -$damage • Fantasma: $monsterHp / $MONSTER_MAX_HP"

        if (monsterHp == 0) {
            defeatMonster(node)
            return
        }

        // Animação rápida de "tomou dano".
        val base = monsterBaseScale ?: return

        hitJob?.cancel()
        hitJob = viewLifecycleOwner.lifecycleScope.launch {
            node.scale = Scale(base.x * 0.8f, base.y * 0.8f, base.z * 0.8f)
            delay(80L)
            node.scale = base
        }
    }

    /** O monstro "avança" e a tela pisca em vermelho. */
    private fun monsterAttackEffect() {
        val node = modelNode ?: return
        val base = monsterBaseScale ?: return

        hitJob?.cancel()
        hitJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                battleLayer?.setBackgroundColor(Color.argb(90, 255, 0, 0))
                node.scale = Scale(base.x * 1.3f, base.y * 1.3f, base.z * 1.3f)
                delay(160L)
                node.scale = base
            } finally {
                battleLayer?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun defeatMonster(node: ModelNode) {
        battleActive = false
        hitJob?.cancel()
        stopCombat()

        val base = monsterBaseScale ?: node.scale

        monstersDefeated++

        // Matar o monstro devolve todas as vidas.
        lives = PLAYER_LIVES
        updateLivesUi()

        statusText?.text =
            "Fantasma banido! Suas vidas voltaram. " +
                    "Pontos: $score • Maior combo: $maxCombo"

        battleJob = viewLifecycleOwner.lifecycleScope.launch {
            for (i in 10 downTo 0) {
                val f = i / 10f
                node.scale = Scale(base.x * f, base.y * f, base.z * f)
                delay(40L)
            }

            delay(800L)

            loadModel() // novo monstro
        }

        updateBattleUi()
    }

    /** Termina a batalha (fuga, derrota do jogador ou pausa). */
    private fun endBattle(message: String? = null) {
        battleActive = false
        hitJob?.cancel()
        stopCombat()

        modelNode?.let { node ->
            monsterBaseScale?.let { node.scale = it }
            node.isEditable = true
            node.isScaleEditable = false
        }

        monsterBaseScale = null

        placeButton?.isEnabled =
            modelNode != null && (mode != Mode.AR || anchorNode != null)

        if (message != null) {
            statusText?.text = message
        }

        updateBattleUi()
    }

    /** Para os círculos e esconde a camada de combate. */
    private fun stopCombat() {
        spawnJob?.cancel()
        spawnJob = null

        clearCircles()

        battleLayer?.setBackgroundColor(Color.TRANSPARENT)

        runCatching { battleDialog?.dismiss() }
        battleDialog = null

        comboText?.visibility = View.GONE
        scoreText?.visibility = View.GONE
        combo = 0
    }

    /**
     * O SceneView (translúcido, sobre a câmera) é desenhado acima de qualquer
     * View da tela, por isso os círculos ficavam atrás do monstro.
     * Uma janela própria (Dialog) fica acima da superfície do SceneView.
     * Ela cobre a tela inteira, exceto a faixa inferior, para o painel e
     * os botões Centralizar / Batalhar / Fugir continuarem tocáveis.
     */
    private fun showBattleDialog() {
        val layerView = battleLayer ?: return
        val host = hostView ?: return

        if (battleDialog?.isShowing == true) return

        // Pode ter ficado preso a um Dialog anterior.
        (layerView.parent as? ViewGroup)?.removeView(layerView)

        val dialog = Dialog(
            requireContext(),
            android.R.style.Theme_Translucent_NoTitleBar
        ).apply {
            setContentView(
                layerView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setCancelable(false)
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            val loc = IntArray(2)
            host.getLocationOnScreen(loc)

            val params = attributes
            params.gravity = Gravity.TOP or Gravity.START
            params.x = loc[0]
            params.y = loc[1]
            params.width = host.width
            params.height =
                (host.height - hudBottomHeight - dp(4)).coerceAtLeast(dp(200))
            attributes = params
        }

        battleDialog = dialog

        runCatching { dialog.show() }
    }

    private fun clearCircles() {
        val layer = battleLayer

        val pending = activeCircles.toList()
        activeCircles.clear()

        pending.forEach { circle ->
            circle.dismiss()
            layer?.removeView(circle)
        }

        // Segurança: qualquer círculo que tenha sobrado na camada.
        if (layer != null) {
            for (i in layer.childCount - 1 downTo 0) {
                val circle = layer.getChildAt(i) as? TargetCircle ?: continue
                circle.dismiss()
                layer.removeViewAt(i)
            }
        }

        linesView?.invalidate()
    }

    // ------------------------------------------------------------------

    private fun modelLocation(fileName: String): String? {
        val context = context ?: return null

        try {
            context.assets.open(fileName).use { }
            return fileName
        } catch (_: Exception) {
            // Procura no armazenamento interno.
        }

        val file = File(context.filesDir, fileName)

        return if (file.isFile) {
            Uri.fromFile(file).toString()
        } else {
            null
        }
    }

    private fun removeModel() {
        // Qualquer remoção do modelo encerra a batalha.
        battleActive = false
        monsterBaseScale = null
        hitJob?.cancel()
        hitJob = null
        battleJob?.cancel()
        battleJob = null
        stopCombat()

        val scene = sceneView

        modelNode?.let { model ->
            runCatching {
                anchorNode?.removeChildNode(model)
                scene?.removeChildNode(model)
                model.destroy()
            }
        }

        modelNode = null

        anchorNode?.let { anchor ->
            runCatching {
                scene?.removeChildNode(anchor)
                anchor.anchor.detach()
                anchor.destroy()
            }
        }

        anchorNode = null
        placementHit = null

        if (mode == Mode.AR) {
            placeButton?.text = "Posicionar"
        }

        updateBattleUi()
    }

    private fun clearScene() {
        generation++
        modelGeneration++

        arWatchdogJob?.cancel()
        arWatchdogJob = null

        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = null

        loadJob?.cancel()
        loadJob = null

        cameraPreview?.let { preview ->
            runCatching {
                cameraProvider?.unbind(preview)
            }
        }

        cameraPreview = null
        cameraProvider = null

        arTracking = false
        cameraStreaming = false

        removeModel()

        sceneView?.let { scene ->
            runCatching {
                scene.lifecycle = null
                scene.destroy()
            }
        }

        sceneView = null
        arView = null

        container?.removeAllViews()
    }

    private fun fullSize() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        musicaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }
        musicaPlayer = null

        availabilityJob?.cancel()
        availabilityJob = null

        clearScene()

        destroyHud()

        hostView = null
        killsText = null
        barsGroup = null
        statusText = null
        placeButton = null
        battleButton = null
        runCatching { gameOverDialog?.dismiss() }
        gameOverDialog = null
        livesText = null
        scoreText = null
        linesView = null
        battleLayer = null
        battleDialog = null
        comboText = null
        hpBar = null
        playerHpBar = null
        battleActive = false
        container = null

        pendingAr = false
        mode = Mode.CHECKING

        super.onDestroyView()
    }
}

/** Resultado de um toque: texto, cor, dano base no monstro e pontos. */
private class Judgement(
    val label: String,
    val color: Int,
    val damage: Int,
    val points: Int
)

/**
 * Círculo numerado estilo osu!: um disco com um anel que encolhe.
 * O momento perfeito é quando o anel encosta no disco.
 * O fragmento recebe a diferença de tempo do toque (errorMs) e julga;
 * se o anel passar da janela de tolerância, o círculo expira (erro).
 */
private class TargetCircle(
    context: Context,
    val number: Int,
    val patternId: Int,
    val cx: Float,
    val cy: Float,
    private val radiusPx: Float,
    private val approachMs: Long,
    lateMs: Long,
    private val canHit: (TargetCircle) -> Boolean,
    private val onTap: (TargetCircle, Long) -> Unit,
    private val onExpire: (TargetCircle) -> Unit
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(240, 128, 6, 24)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radiusPx * 0.1f
        color = Color.rgb(236, 226, 220)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radiusPx * 0.12f
        color = Color.argb(235, 236, 226, 220)
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(246, 238, 232)
        textAlign = Paint.Align.CENTER
        textSize = radiusPx * 1.0f
        typeface = Typeface.DEFAULT_BOLD
    }

    private var progress = 1f
    private var resolved = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = approachMs + lateMs
        interpolator = LinearInterpolator()

        addUpdateListener {
            val elapsed = it.currentPlayTime
            progress = (1f - elapsed.toFloat() / approachMs).coerceIn(0f, 1f)
            invalidate()
        }

        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!resolved) {
                    resolved = true
                    onExpire(this@TargetCircle)
                }
            }
        })
    }

    fun start() {
        animator.start()
    }

    /** Remove sem contar como acerto nem erro. */
    fun dismiss() {
        resolved = true
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f

        canvas.drawCircle(x, y, radiusPx, fillPaint)
        canvas.drawCircle(x, y, radiusPx, borderPaint)

        val textY = y - (numberPaint.descent() + numberPaint.ascent()) / 2f
        canvas.drawText(number.toString(), x, textY, numberPaint)

        // Anel de aproximação
        canvas.drawCircle(x, y, radiusPx * (1f + 1.5f * progress), ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || resolved) {
            return false
        }

        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        val distance = sqrt(dx * dx + dy * dy)

        // Margem de tolerância um pouco maior que o disco.
        if (distance > radiusPx * 1.4f) {
            return false // deixa o toque cair na camada
        }

        // Fora da ordem (ainda não é a vez deste número): ignora.
        if (!canHit(this)) {
            return true
        }

        val errorMs = animator.currentPlayTime - approachMs
        onTap(this, errorMs)
        return true
    }
}

/** Linhas pontilhadas ligando 1 → 2 → 3… de uma mesma sequência. */
private class LinkLinesView(
    context: Context,
    private val provider: () -> List<TargetCircle>
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
        color = Color.argb(150, 220, 40, 55)
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val list = provider()

        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]

            if (a.patternId == b.patternId && b.number == a.number + 1) {
                canvas.drawLine(a.cx, a.cy, b.cx, b.cy, paint)
            }
        }
    }
}


/**
 * Botão da interface com visual sombrio: cantos arredondados, borda
 * vermelha (principal) ou cinza (secundário), texto em maiúsculas.
 */
private class HorrorButton(
    context: Context,
    primary: Boolean
) : TextView(context) {

    private val density = resources.displayMetrics.density
    private var primaryStyle = primary

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(242, 234, 230))
        textSize = 14f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = 0.14f
        isClickable = true
        isFocusable = true
        setPadding(
            (16 * density).toInt(),
            (12 * density).toInt(),
            (16 * density).toInt(),
            (12 * density).toInt()
        )
        applyStyle()
    }

    fun setPrimary(value: Boolean) {
        if (primaryStyle != value) {
            primaryStyle = value
            applyStyle()
        }
    }

    private fun applyStyle() {
        val shape = GradientDrawable().apply {
            cornerRadius = 14 * density

            if (primaryStyle) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.rgb(196, 20, 38),
                    Color.rgb(108, 6, 20)
                )
                setStroke((1.5f * density).toInt(), Color.argb(210, 255, 72, 88))
            } else {
                setColor(Color.argb(228, 16, 16, 22))
                setStroke((1.5f * density).toInt(), Color.argb(150, 190, 190, 200))
            }
        }

        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            shape,
            null
        )
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(text?.toString()?.uppercase(), type)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}

/** Barra de vida arredondada, com degradê e animação suave. */
private class HpBarView(
    context: Context,
    private val colorStart: Int,
    private val colorEnd: Int
) : View(context) {

    var max: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            field = value
            animateTo(value.toFloat())
        }

    private var shown = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 38, 38, 44)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun animateTo(target: Float) {
        animator?.cancel()

        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 220L
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            colorStart, colorEnd,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val fraction = (shown / max).coerceIn(0f, 1f)

        if (fraction > 0f) {
            rect.set(0f, 0f, maxOf(h, w * fraction), h)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }
}
