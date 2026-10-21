package com.example.pokegotchi

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/**
 * SOLO PRUEBAS (cuenta secundaria): pantalla en primer plano que ejecuta el arnes de QA ([QaHarness]) con el movil despierto.
 * Hace falta porque Android CONGELA los procesos en segundo plano de la cuenta secundaria (0 % de CPU, la prueba no avanza).
 * Se lanza desde MainActivity con `--es debug_qa <suites> [--ei n N] [--el seed S]`. El propio arnes se niega a correr en la principal.
 */
class QaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val suites = (intent.getStringExtra("suites") ?: "conflicts").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val seed = intent.getLongExtra("seed", 1L)
        val n = intent.getIntExtra("n", 200)
        val tv = TextView(this).apply { text = "QA en marcha: $suites (n=$n, seed=$seed) - no cierres esta pantalla"; textSize = 18f; setPadding(40, 120, 40, 40) }
        setContentView(tv)
        val app = applicationContext
        Thread {
            val report = try { QaHarness.run(app, suites, seed, n) } catch (t: Throwable) { "QA FALLO: $t" }
            try { java.io.File(app.filesDir, "qa_report_${suites.joinToString("_")}.txt").writeText(report) } catch (_: Exception) {}
            DebugLog.log(app, "QA terminado ($suites): " + report.lines().lastOrNull { it.startsWith("RESUMEN") || it.startsWith("QA") })
            runOnUiThread { tv.text = "QA terminado: " + report.lines().lastOrNull { it.startsWith("RESUMEN") }; finish() }
        }.start()
    }
}
