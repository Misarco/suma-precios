package com.mbg.sumaprecios

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var frozenImage: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var chooseBar: View
    private lateinit var numbersList: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var totalText: TextView
    private lateinit var countText: TextView
    private lateinit var torchButton: ImageButton
    private lateinit var undoButton: MaterialButton

    private lateinit var store: Store
    private lateinit var adapter: ItemsAdapter
    private lateinit var executor: ExecutorService

    private val items = mutableListOf<SaleItem>()
    private val detector = Detector()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var mode = NumberMode.AUTO
    private var letters = true

    /** Registro de cambios de la venta en curso, para Deshacer (se vacía en Nueva venta). */
    private sealed class Change {
        data class Added(val id: Long) : Change()
        data class Removed(val item: SaleItem, val index: Int) : Change()
        data class Edited(val id: Long, val oldCents: Long, val oldDiscount: Int) : Change()
    }
    private val undoStack = ArrayDeque<Change>()
    private var camera: Camera? = null
    private var torchOn = false

    @Volatile private var frozen = false
    private var currentChoices: List<Candidate> = emptyList()
    private val choiceItemIds = mutableMapOf<Int, Long>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else overlay.hint = "Sin permiso de cámara. Actívalo en los ajustes de Android."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        frozenImage = findViewById(R.id.frozenImage)
        overlay = findViewById(R.id.overlay)
        chooseBar = findViewById(R.id.chooseBar)
        numbersList = findViewById(R.id.numbersList)
        emptyHint = findViewById(R.id.emptyHint)
        totalText = findViewById(R.id.totalText)
        countText = findViewById(R.id.countText)
        torchButton = findViewById(R.id.btnTorch)
        undoButton = findViewById(R.id.btnUndo)

        store = Store(this)
        executor = Executors.newSingleThreadExecutor()

        items.addAll(store.loadItems())
        adapter = ItemsAdapter(items, ::showEdit, ::showItemMenu) { store.longPressMs }
        numbersList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        numbersList.adapter = adapter
        if (items.isNotEmpty()) numbersList.scrollToPosition(items.size - 1)
        attachSwipeToDelete()
        updateTotal()
        updateUndo()
        undoButton.setOnClickListener { undo() }

        overlay.setZoneFractions(store.zone)
        overlay.onZoneChanged = { store.zone = it }

        overlay.onChoiceTap = ::onChoiceTap
        findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener { unfreeze() }
        findViewById<MaterialButton>(R.id.btnNewSale).setOnClickListener { confirmNewSale() }
        findViewById<MaterialButton>(R.id.btnManual).setOnClickListener { showManualAdd() }
        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener { showHistory() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        torchButton.setOnClickListener { toggleTorch() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        val newMode = store.mode
        val newLetters = store.letters
        if (newMode != mode || newLetters != letters) detector.reset()
        mode = newMode
        letters = newLetters
        detector.cooldownMs = store.pauseMs
        overlay.setZoneFractions(store.zone)
    }

    override fun onPause() {
        super.onPause()
        store.saveItems(items)
        if (torchOn) toggleTorch()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer.close()
    }

    // ---------- Cámara y lectura ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, TextAnalyzer())

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                overlay.hint = "No se pudo abrir la cámara"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class TextAnalyzer : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (frozen || media == null) {
                proxy.close(); return
            }
            val rot = proxy.imageInfo.rotationDegrees
            val w = if (rot % 180 == 0) proxy.width else proxy.height
            val h = if (rot % 180 == 0) proxy.height else proxy.width
            recognizer.process(InputImage.fromMediaImage(media, rot))
                .addOnSuccessListener { text -> onTextRead(text, w, h) }
                .addOnCompleteListener { proxy.close() }
        }
    }

    /** Se ejecuta en el hilo principal con lo que ha leído en cada imagen. */
    private fun onTextRead(text: Text, imgW: Int, imgH: Int) {
        if (frozen) return
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f || imgW <= 0 || imgH <= 0) return

        // Pasar de coordenadas de la imagen a coordenadas de la pantalla
        val scale = max(vw / imgW, vh / imgH)
        val dx = (vw - imgW * scale) / 2f
        val dy = (vh - imgH * scale) / 2f

        val lines = text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                line.elements.mapNotNull { e ->
                    e.boundingBox?.let { r ->
                        Word(
                            e.text,
                            Box(r.left * scale + dx, r.top * scale + dy, r.right * scale + dx, r.bottom * scale + dy)
                        )
                    }
                }
            }
        }

        // Solo números enteros dentro de la zona de lectura (los cortados en el borde no valen)
        val z = overlay.zone
        val cands = NumberParser.extract(lines, mode, letters).filter {
            it.box.left >= z.left && it.box.right <= z.right && it.box.top >= z.top && it.box.bottom <= z.bottom
        }

        val now = SystemClock.elapsedRealtime()
        when (val r = detector.process(cands, now)) {
            is Detector.Result.Add -> {
                addItem(r.candidate.cents)
                vibrate()
                overlay.flash(r.candidate.box)
                overlay.startCooldown(detector.cooldownMs)
            }
            is Detector.Result.Choose -> freeze(r.candidates)
            Detector.Result.None -> Unit
        }
        overlay.setLive(cands, detector.blockedValues())
    }

    // ---------- Varios números: congelar y elegir ----------

    private fun freeze(choices: List<Candidate>) {
        frozen = true
        previewView.bitmap?.let {
            frozenImage.setImageBitmap(it)
            frozenImage.isVisible = true
        }
        currentChoices = choices
        choiceItemIds.clear()
        overlay.setChoices(choices)
        chooseBar.isVisible = true
        vibrate()
    }

    private fun onChoiceTap(i: Int) {
        val existing = choiceItemIds.remove(i)
        if (existing != null) {
            removeItemById(existing)
            overlay.setChosen(i, false)
        } else {
            val item = addItem(currentChoices[i].cents)
            choiceItemIds[i] = item.id
            overlay.setChosen(i, true)
        }
    }

    private fun unfreeze() {
        frozenImage.isVisible = false
        frozenImage.setImageDrawable(null)
        overlay.setChoices(emptyList())
        chooseBar.isVisible = false
        detector.resume(SystemClock.elapsedRealtime())
        overlay.startCooldown(detector.cooldownMs)
        frozen = false
    }

    // ---------- Venta en curso ----------

    private fun addItem(cents: Long): SaleItem {
        val item = SaleItem(System.nanoTime(), cents)
        items.add(item)
        record(Change.Added(item.id))
        adapter.notifyItemInserted(items.size - 1)
        numbersList.scrollToPosition(items.size - 1)
        afterChange()
        return item
    }

    private fun removeItemById(id: Long, recordChange: Boolean = true) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val item = items.removeAt(idx)
        if (recordChange) record(Change.Removed(item.copy(), idx))
        adapter.notifyItemRemoved(idx)
        // Si estaba marcado en la imagen congelada, se desmarca
        choiceItemIds.entries.firstOrNull { it.value == id }?.let {
            choiceItemIds.remove(it.key)
            overlay.setChosen(it.key, false)
        }
        afterChange()
    }

    private fun editItem(item: SaleItem, cents: Long = item.cents, discount: Int = item.discount) {
        if (cents == item.cents && discount == item.discount) return
        record(Change.Edited(item.id, item.cents, item.discount))
        item.cents = cents
        item.discount = discount
        refreshItem(item)
    }

    // ---------- Deshacer ----------

    private fun record(change: Change) {
        undoStack.addLast(change)
        while (undoStack.size > 200) undoStack.removeFirst()
    }

    private fun undo() {
        when (val ch = undoStack.removeLastOrNull() ?: return) {
            is Change.Added -> removeItemById(ch.id, recordChange = false)
            is Change.Removed -> {
                val idx = ch.index.coerceIn(0, items.size)
                items.add(idx, ch.item.copy())
                adapter.notifyItemInserted(idx)
                numbersList.scrollToPosition(idx)
                afterChange()
            }
            is Change.Edited -> {
                val item = items.firstOrNull { it.id == ch.id }
                if (item != null) {
                    item.cents = ch.oldCents
                    item.discount = ch.oldDiscount
                    refreshItem(item)
                }
            }
        }
        updateUndo()
    }

    private fun updateUndo() {
        val n = undoStack.size
        undoButton.isEnabled = n > 0
        undoButton.text = if (n > 0) n.toString() else ""
        undoButton.alpha = if (n > 0) 1f else 0.4f
    }

    private fun attachSwipeToDelete() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.DOWN) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val item = items.getOrNull(vh.bindingAdapterPosition)
                if (item == null) {
                    adapter.notifyDataSetChanged()
                    return
                }
                removeItemById(item.id)
                Snackbar.make(numbersList, "Borrado ${money(item.finalCents)} €", Snackbar.LENGTH_SHORT)
                    .setAnchorView(numbersList)
                    .setAction("Deshacer") { undo() }
                    .show()
            }
        }).attachToRecyclerView(numbersList)
    }

    private fun refreshItem(item: SaleItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) adapter.notifyItemChanged(idx)
        afterChange()
    }

    private fun afterChange() {
        updateTotal()
        updateUndo()
        store.saveItems(items)
    }

    private fun updateTotal() {
        val total = items.sumOf { it.finalCents }
        totalText.text = getString(R.string.total_value, money(total))
        countText.text = when (items.size) {
            0 -> ""
            1 -> "1 número"
            else -> "${items.size} números"
        }
        emptyHint.isVisible = items.isEmpty()
    }

    // ---------- Diálogos ----------

    private fun numberInput(prefill: String, allowDecimals: Boolean = true): EditText =
        EditText(this).apply {
            if (allowDecimals) {
                // Acepta coma y punto, pero con el teclado numérico
                keyListener = DigitsKeyListener.getInstance("0123456789,.")
                setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            } else {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            textSize = 24f
            setText(prefill)
            setSelection(text.length)
        }

    private fun padded(v: View): View = FrameLayout(this).apply {
        val p = (24 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(v)
    }

    /** Muestra un diálogo con un campo de número. [onOk] devuelve un error o null si todo fue bien. */
    private fun inputDialog(
        title: String,
        input: EditText,
        neutral: Pair<String, () -> Unit>? = null,
        onOk: (String) -> String?,
    ) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(padded(input))
            .setPositiveButton("Aceptar", null)
            .setNegativeButton("Cancelar", null)
        if (neutral != null) builder.setNeutralButton(neutral.first) { _, _ -> neutral.second() }
        val dialog = builder.create()
        val submit: () -> Unit = {
            val err = onOk(input.text.toString().trim())
            if (err == null) {
                dialog.dismiss()
            } else {
                input.error = err
            }
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
            input.selectAll()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun parseTyped(txt: String): Long? =
        NumberParser.toCents(txt.replace(" ", ""), NumberMode.AUTO)

    private fun showEdit(pos: Int) {
        val item = items.getOrNull(pos) ?: return
        inputDialog(
            "Editar precio",
            numberInput(money(item.cents).replace(".", "")),
            neutral = "Borrar" to { removeItemById(item.id) },
        ) { txt ->
            if (txt.isEmpty()) {
                removeItemById(item.id); null
            } else {
                val c = parseTyped(txt)
                if (c == null || c <= 0) "Número no válido"
                else { editItem(item, cents = c); null }
            }
        }
    }

    private fun showItemMenu(pos: Int) {
        val item = items.getOrNull(pos) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(money(item.finalCents) + " €")
            .setItems(arrayOf("Editar", "Descuento (%)", "Borrar")) { _, which ->
                when (which) {
                    0 -> showEdit(pos)
                    1 -> showDiscount(item)
                    2 -> removeItemById(item.id)
                }
            }
            .show()
    }

    private fun showDiscount(item: SaleItem) {
        val input = numberInput(if (item.discount > 0) item.discount.toString() else "", allowDecimals = false)
        input.hint = "%"
        inputDialog("Descuento en % para ${money(item.cents)} €", input) { txt ->
            val d = if (txt.isEmpty()) 0 else txt.toIntOrNull()
            if (d == null || d < 0 || d > 100) "Pon un número del 0 al 100"
            else { editItem(item, discount = d); null }
        }
    }

    private fun showManualAdd() {
        inputDialog("Añadir precio a mano", numberInput("")) { txt ->
            val c = parseTyped(txt)
            if (c == null || c <= 0) "Número no válido" else { addItem(c); null }
        }
    }

    private fun confirmNewSale() {
        if (items.isEmpty()) {
            Toast.makeText(this, "La venta está vacía", Toast.LENGTH_SHORT).show()
            return
        }
        val total = items.sumOf { it.finalCents }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Empezar una nueva venta?")
            .setMessage("El total de esta venta (${money(total)} €) se guardará en el historial y el cuadro quedará vacío.")
            .setPositiveButton("Nueva venta") { _, _ ->
                store.addHistory(total)
                val n = items.size
                items.clear()
                undoStack.clear()
                adapter.notifyItemRangeRemoved(0, n)
                afterChange()
                detector.reset()
                Toast.makeText(this, "Venta guardada: ${money(total)} €", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showHistory() {
        val history = store.loadHistory()
        val b = MaterialAlertDialogBuilder(this).setTitle("Últimas ventas")
        if (history.isEmpty()) {
            b.setMessage("Todavía no hay ventas guardadas. Se guardan al pulsar Nueva venta.")
        } else {
            b.setItems(history.mapIndexed { i, t ->
                (if (i == 0) "Última:  " else "${i + 1}.  ") + money(t) + " €"
            }.toTypedArray(), null)
        }
        b.setPositiveButton("Cerrar", null).show()
    }

    // ---------- Linterna y vibración ----------

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Este móvil no tiene linterna", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton.isSelected = torchOn
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vib?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
