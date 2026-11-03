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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
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

    private var mode = Mode.CHECKING

    private var container: FrameLayout? = null
    private var placeButton: ExtendedFloatingActionButton? = null
    private var statusText: TextView? = null
    private var overlay: View? = null

    // Batalha
    private var battleButton: ExtendedFloatingActionButton? = null
    private var battleLayer: FrameLayout? = null
    private var battleDialog: Dialog? = null
    private var gameOverDialog: Dialog? = null
    private var livesText: TextView? = null
    private var comboText: TextView? = null
    private var scoreText: TextView? = null
    private var linesView: LinkLinesView? = null
    private var hpBar: ProgressBar? = null
    private var playerHpBar: ProgressBar? = null

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
        placeButton = view.findViewById(R.id.place)

        createControls()

        // Se o jogo já tinha acabado (0 vidas), mostra o Game Over de novo.
        if (lives <= 0) {
            view.post {
                if (container != null && lives <= 0) {
                    showGameOver()
                }
            }
        }

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
    }

    override fun onPause() {
        // Evita que os círculos expirem (e tirem vida) com o app em segundo plano.
        if (battleActive) {
            endBattle("Batalha pausada.")
        }
        super.onPause()
    }

    private fun createControls() {
        val parent = container?.parent as? ViewGroup ?: return

        val layer = FrameLayout(requireContext())

        // Camada dos círculos. Ela NÃO fica nesta tela: é exibida numa janela
        // própria (veja showBattleDialog), para ficar na frente do modelo 3D.
        // Tocar nela (fora de um círculo) conta como erro.
        battleLayer = FrameLayout(requireContext()).apply {
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

        // Linhas pontilhadas ligando os círculos numerados (fica por baixo deles)
        linesView = LinkLinesView(requireContext()) { activeCircles }

        battleLayer?.addView(
            linesView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Texto de combo
        comboText = TextView(requireContext()).apply {
            visibility = View.GONE
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(255, 213, 0))
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
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

        // Placar
        scoreText = TextView(requireContext()).apply {
            visibility = View.GONE
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
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

        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.argb(190, 25, 30, 35))
        }

        statusText = TextView(requireContext()).apply {
            text = "Preparando visualização…"
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        panel.addView(statusText)

        // Vidas da partida (corações)
        livesText = TextView(requireContext()).apply {
            textSize = 16f
            setTextColor(Color.rgb(255, 90, 90))
        }

        panel.addView(
            livesText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        updateLivesUi()

        // Barra de vida do monstro (vermelha)
        hpBar = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            visibility = View.GONE
            max = MONSTER_MAX_HP
            progressTintList = ColorStateList.valueOf(Color.RED)
        }

        panel.addView(
            hpBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10)
            ).apply { topMargin = dp(6) }
        )

        // Barra de vida do jogador (verde)
        playerHpBar = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            visibility = View.GONE
            max = PLAYER_MAX_HP
            progressTintList =
                ColorStateList.valueOf(Color.rgb(60, 200, 90))
        }

        panel.addView(
            playerHpBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10)
            ).apply { topMargin = dp(4) }
        )

        layer.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(85)
            }
        )

        // Botão de batalha, ao lado do botão "Centralizar"
        battleButton = ExtendedFloatingActionButton(requireContext()).apply {
            text = "Batalhar"
            isEnabled = false
            setOnClickListener {
                if (battleActive) {
                    endBattle("Você fugiu da batalha.")
                } else {
                    startBattle()
                }
            }
        }

        layer.addView(
            battleButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )

        placeButton?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignBattleButton()
        }
        battleButton?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignBattleButton()
        }

        parent.addView(
            layer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay = layer

        parent.findViewById<View>(R.id.titleImage)?.bringToFront()
        placeButton?.bringToFront()
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

    /** Coloca o botão de batalha ao lado direito de "Centralizar". */
    private fun alignBattleButton() {
        val place = placeButton ?: return
        val battle = battleButton ?: return
        val layer = overlay ?: return

        val p = IntArray(2)
        val l = IntArray(2)
        place.getLocationOnScreen(p)
        layer.getLocationOnScreen(l)

        battle.translationX =
            (p[0] - l[0] + place.width + dp(12)).toFloat()
        battle.translationY =
            (p[1] - l[1] + (place.height - battle.height) / 2).toFloat()
    }

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
            isEnabled = battleActive || canBattle()
        }

        val visibility = if (battleActive) View.VISIBLE else View.GONE
        hpBar?.visibility = visibility
        playerHpBar?.visibility = visibility
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
            "Toque nos círculos em ordem (1, 2, 3…) quando o anel fechar. " +
                    "Vermelha: monstro • Verde: você."

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

        var x = minX + Random.nextFloat() * (maxX - minX)
        var y = minY + Random.nextFloat() * (maxY - minY)

        for (number in 1..count) {
            if (!battleActive) return

            spawnCircle(patternId, number, x, y, radius, approach)

            // Próximo círculo da sequência: perto do anterior.
            val angle = Random.nextDouble(0.0, 2 * PI)
            val distance = (dp(110) + Random.nextInt(dp(90))).toFloat()

            x = (x + cos(angle).toFloat() * distance).coerceIn(minX, maxX)
            y = (y + sin(angle).toFloat() * distance).coerceIn(minY, maxY)

            delay(beat)
        }
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
            "O monstro atacou! Sua vida: $playerHp / $PLAYER_MAX_HP"

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
                "O monstro te derrotou! Restam $restantes. " +
                        "Toque em Batalhar para tentar de novo."
            )
        }
    }

    private fun updateLivesUi() {
        livesText?.text =
            "Vidas: " + "❤".repeat(lives) + "♡".repeat(PLAYER_LIVES - lives)
    }

    /** Tela de Game Over (em janela própria, acima do SceneView). */
    private fun showGameOver() {
        if (gameOverDialog?.isShowing == true) return

        val ctx = requireContext()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 10, 10, 15))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isClickable = true // bloqueia toques no que está atrás
        }

        content.addView(
            TextView(ctx).apply {
                text = "GAME OVER"
                textSize = 44f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(230, 50, 50))
                setShadowLayer(12f, 0f, 0f, Color.BLACK)
            }
        )

        content.addView(
            TextView(ctx).apply {
                text = "Você perdeu todas as vidas.\n" +
                        "Monstros derrotados: $monstersDefeated"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )

        content.addView(
            ExtendedFloatingActionButton(ctx).apply {
                text = "Jogar novamente"
                setOnClickListener { restartGame() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(32) }
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

        statusText?.text = "Nova partida! Toque em Batalhar."
        updateBattleUi()
    }

    private fun hitMonster(damage: Int) {
        if (!battleActive) return
        val node = modelNode ?: return

        monsterHp = (monsterHp - damage).coerceAtLeast(0)
        hpBar?.progress = monsterHp
        statusText?.text =
            "Acertou! -$damage • Monstro: $monsterHp / $MONSTER_MAX_HP"

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

        statusText?.text =
            "Monstro derrotado! Pontos: $score • Maior combo: $maxCombo"

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
        val host = overlay ?: return

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
            params.height = (host.height - dp(190)).coerceAtLeast(dp(200))
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

        overlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        overlay = null
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
        color = Color.argb(235, 255, 105, 180)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radiusPx * 0.1f
        color = Color.WHITE
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radiusPx * 0.12f
        color = Color.argb(230, 255, 255, 255)
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
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
        color = Color.argb(170, 255, 255, 255)
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
