package com.example.pokegotchi

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnostico PERSISTENTE, ademas de logcat: logcat se vacia solo en menos de una
 * hora en un movil activo (es un buffer pequeño de 2MB COMPARTIDO por todo el sistema, no algo
 * que dependa de esta app - cada notificacion, sensor, app en segundo plano escribe ahi tambien),
 * asi que "revisar los logs del dia" no era posible solo con el - pedido explicito del usuario
 * tras comprobarlo.
 *
 * Aqui se guarda ADEMAS en un archivo propio en el almacenamiento privado de la app
 * (filesDir/pokegotchi_debug.log): sobrevive a que logcat se vacie, a que el proceso muera y se
 * reinicie, a reinstalar la app encima (NO a desinstalarla del todo) - con un tope de tamaño
 * (recorta la mitad mas vieja al pasarse) para que no crezca sin limite.
 *
 * Sacarlo del dispositivo: adb shell run-as com.example.pokegotchi cat files/pokegotchi_debug.log
 */
object DebugLog {
    private const val TAG = "PokeGotchiDbg"
    private const val FILE_NAME = "pokegotchi_debug.log"
    private const val MAX_BYTES = 1_000_000L   // ~1MB, de sobra para un dia entero de uso normal
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** Escribe [msg] en logcat (igual que antes, para verlo en directo con adb) Y en el archivo
     *  persistente. Nunca lanza - un fallo al escribir el log no debe romper la accion real que
     *  lo dispara. */
    fun log(context: Context, msg: String) {
        val line = "[pid=${Process.myPid()}] $msg"
        Log.i(TAG, line)
        try {
            val f = file(context)
            f.appendText("${fmt.format(Date())} $line\n")
            if (f.length() > MAX_BYTES) trim(f)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo escribir el log persistente: $e")
        }
    }

    /** Archivo del log persistente. */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Se queda solo con la mitad final del archivo (recorta justo despues del primer salto de
     *  linea a partir de la mitad, para no dejar ninguna linea a medias) - simple y barato, no
     *  hace falta contar lineas exactas ni fechas. */
    private fun trim(f: File) {
        val bytes = f.readBytes()
        var cut = bytes.size / 2
        while (cut < bytes.size && bytes[cut] != '\n'.code.toByte()) cut++
        if (cut < bytes.size) cut++
        f.writeBytes(bytes.copyOfRange(cut, bytes.size))
    }
}
