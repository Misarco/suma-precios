package com.mbg.sumaprecios

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var pauseSlider: Slider
    private lateinit var pauseValue: TextView
    private lateinit var longSlider: Slider
    private lateinit var longValue: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var lettersSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = Store(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        pauseSlider = findViewById(R.id.pauseSlider)
        pauseValue = findViewById(R.id.pauseValue)
        longSlider = findViewById(R.id.longSlider)
        longValue = findViewById(R.id.longValue)
        modeGroup = findViewById(R.id.modeGroup)
        lettersSwitch = findViewById(R.id.lettersSwitch)

        bind()

        // Los deslizadores van en décimas de segundo (5 = 0,5 s) para evitar fallos de redondeo
        pauseSlider.addOnChangeListener { _, value, fromUser ->
            pauseValue.text = seconds(value)
            if (fromUser) store.pauseMs = value.toLong() * 100
        }
        longSlider.addOnChangeListener { _, value, fromUser ->
            longValue.text = seconds(value)
            if (fromUser) store.longPressMs = value.toLong() * 100
        }
        modeGroup.setOnCheckedChangeListener { _, id ->
            store.mode = when (id) {
                R.id.modeEnteros -> NumberMode.ENTEROS
                R.id.modeCentimos -> NumberMode.CENTIMOS
                else -> NumberMode.AUTO
            }
        }

        lettersSwitch.setOnCheckedChangeListener { _, checked -> store.letters = checked }

        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("¿Restablecer ajustes?")
                .setMessage("Vuelven los valores de fábrica: pausa de 1 s, mantener pulsado 1 s, números automáticos, comparar con letras activado y la zona de lectura a su tamaño original. La venta en curso y el historial no se tocan.")
                .setPositiveButton("Restablecer") { _, _ ->
                    store.resetSettings()
                    bind()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun bind() {
        lettersSwitch.isChecked = store.letters
        val pause = (store.pauseMs / 100).toFloat().coerceIn(pauseSlider.valueFrom, pauseSlider.valueTo)
        pauseSlider.value = pause
        pauseValue.text = seconds(pause)
        val long = (store.longPressMs / 100).toFloat().coerceIn(longSlider.valueFrom, longSlider.valueTo)
        longSlider.value = long
        longValue.text = seconds(long)
        modeGroup.check(
            when (store.mode) {
                NumberMode.AUTO -> R.id.modeAuto
                NumberMode.ENTEROS -> R.id.modeEnteros
                NumberMode.CENTIMOS -> R.id.modeCentimos
            }
        )
    }

    private fun seconds(tenths: Float) =
        String.format(Locale("es", "ES"), "%.1f s", tenths / 10f)
}
