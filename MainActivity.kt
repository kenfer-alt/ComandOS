package com.comandos.os

import android.app.Activity
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private enum class Mode { SETUP, SETUP_CONFIRM, LOCKED, LOGIN, READY }

    // Colores de cada comando
    private val AZUL = Color.parseColor("#4DA3FF")      // 1 Comenzar
    private val VERDE = Color.parseColor("#39FF14")     // 2 Mostrar Aplicaciones
    private val ROJO = Color.parseColor("#FF4444")      // 3 Entrar a
    private val AMARILLO = Color.parseColor("#FFE600")  // 4 Mostrar información
    private val MORADO = Color.parseColor("#B266FF")    // 5 Desinstalar
    private val DORADO = Color.parseColor("#FFD700")    // 6 Ayuda / sistema
    private val BLANCO = Color.parseColor("#CCCCCC")

    private lateinit var prefs: SharedPreferences
    private lateinit var header: TextView
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var prompt: TextView
    private lateinit var input: EditText
    private lateinit var scale: ScaleGestureDetector

    private var mode = Mode.LOCKED
    private var pendingPass = ""
    private var textSp = 15f
    private var typeface: Typeface = Typeface.MONOSPACE

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (mode == Mode.READY) { mode = Mode.LOCKED; setMasked(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("comandos", MODE_PRIVATE)
        textSp = prefs.getFloat("size", 15f)
        typeface = fontByName(prefs.getString("font", "terminal") ?: "terminal") ?: Typeface.MONOSPACE
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        buildUi()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))

        scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setSize(textSp * d.scaleFactor); return true
            }
        })

        if (prefs.getString("hash", null) == null) {
            mode = Mode.SETUP
            print("Bienvenido a comandOS.", DORADO)
            print("Crea una clave segura (mínimo 6 caracteres):", DORADO)
            setMasked(true)
        } else {
            mode = Mode.LOCKED
            print("comandOS listo. Escribe comenzar para entrar.", DORADO)
        }
    }

    override fun onDestroy() { super.onDestroy(); unregisterReceiver(screenOff) }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); focusInput() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* Un launcher no debe cerrarse */ }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scale.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }
        header = TextView(this).apply {
            text = "comandOS  |  escribe ayuda"
            setTextColor(DORADO)
        }
        scroll = ScrollView(this)
        out = TextView(this).apply { setTextColor(BLANCO); setTextIsSelectable(true) }
        scroll.addView(out)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prompt = TextView(this).apply { text = "> "; setTextColor(VERDE) }
        input = EditText(this).apply {
            background = null
            setTextColor(Color.WHITE)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        row.addView(prompt)
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        setContentView(root)
        out.setOnClickListener { focusInput() }
        applyFont()
        setMasked(false)
    }

    private fun applyFont() {
        for (t in listOf(header, out, prompt, input)) {
            t.typeface = typeface
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        }
    }

    private fun setSize(sp: Float) {
        textSp = sp.coerceIn(8f, 40f)
        prefs.edit().putFloat("size", textSp).apply()
        applyFont()
    }

    private fun setMasked(masked: Boolean) {
        input.inputType = InputType.TYPE_CLASS_TEXT or
            if (masked) InputType.TYPE_TEXT_VARIATION_PASSWORD
            else (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input.typeface = typeface
        prompt.setTextColor(if (masked) ROJO else VERDE)
        focusInput()
    }

    private fun focusInput() {
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun print(text: String, color: Int = BLANCO) {
        val s = SpannableString(text + "\n")
        s.setSpan(ForegroundColorSpan(color), 0, s.length, 0)
        out.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- Clave ----------
    private fun hash(pass: String, salt: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest((salt + pass).toByteArray()), Base64.NO_WRAP)

    private fun savePass(pass: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        prefs.edit().putString("salt", salt).putString("hash", hash(pass, salt)).apply()
    }

    private fun checkPass(pass: String): Boolean {
        val salt = prefs.getString("salt", "") ?: ""
        return hash(pass, salt) == prefs.getString("hash", null)
    }

    // ---------- Entrada ----------
    private fun submit() {
        val raw = input.text.toString().trim()
        input.setText("")
        if (raw.isEmpty()) return

        when (mode) {
            Mode.SETUP -> {
                print("> " + "*".repeat(raw.length), ROJO)
                if (raw.length < 6) { print("Muy corta. Usa mínimo 6 caracteres.", ROJO); return }
                pendingPass = raw
                mode = Mode.SETUP_CONFIRM
                print("Repite la clave para confirmar:", DORADO)
            }
            Mode.SETUP_CONFIRM -> {
                print("> " + "*".repeat(raw.length), ROJO)
                if (raw == pendingPass) {
                    savePass(raw); pendingPass = ""
                    mode = Mode.READY; setMasked(false)
                    print("Clave guardada. Sistema iniciado.", VERDE)
                } else {
                    mode = Mode.SETUP; pendingPass = ""
                    print("No coinciden. Crea la clave de nuevo:", ROJO)
                }
            }
            Mode.LOGIN -> {
                print("> " + "*".repeat(raw.length), ROJO)
                if (checkPass(raw)) {
                    mode = Mode.READY; setMasked(false)
                    print("Acceso concedido.", VERDE)
                } else {
                    mode = Mode.LOCKED; setMasked(false)
                    print("Clave incorrecta.", ROJO)
                }
            }
            Mode.LOCKED -> runLocked(raw)
            Mode.READY -> runCommand(raw)
        }
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase().trim().replace(Regex("\\s+"), " ")

    private fun runLocked(raw: String) {
        when (norm(raw)) {
            "comenzar" -> {
                print("> $raw", AZUL)
                print("Escribe tu clave:", AZUL)
                mode = Mode.LOGIN; setMasked(true)
            }
            "ayuda" -> { print("> $raw", DORADO); showHelp() }
            else -> { print("> $raw", ROJO); print("Sistema bloqueado. Escribe comenzar.", ROJO) }
        }
    }

    private fun runCommand(raw: String) {
        val c = norm(raw)
        when {
            c == "comenzar" -> { print("> $raw", AZUL); print("Ya estás dentro del sistema.", AZUL) }

            c == "mostrar aplicaciones" -> {
                print("> $raw", VERDE)
                val list = apps()
                list.forEachIndexed { i, a -> print("${i + 1}. ${a.first}", VERDE) }
                print("Total: ${list.size} aplicaciones", VERDE)
            }

            c.startsWith("entrar a ") || c.startsWith("entrar ") -> {
                print("> $raw", ROJO)
                val name = c.removePrefix("entrar a ").removePrefix("entrar ")
                withApp(name, ROJO) { label, pkg ->
                    val i = packageManager.getLaunchIntentForPackage(pkg)
                    if (i == null) print("No se puede abrir $label.", ROJO)
                    else { print("Abriendo $label...", ROJO); startActivity(i) }
                }
            }

            c.startsWith("mostrar informacion de ") -> {
                print("> $raw", AMARILLO)
                withApp(c.removePrefix("mostrar informacion de "), AMARILLO) { label, pkg -> showInfo(label, pkg) }
            }

            c.startsWith("desinstalar ") -> {
                print("> $raw", MORADO)
                withApp(c.removePrefix("desinstalar "), MORADO) { label, pkg ->
                    print("Solicitando desinstalar $label (Android te pedirá confirmar)...", MORADO)
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }

            c == "ayuda" -> { print("> $raw", DORADO); showHelp() }
            c == "limpiar" -> out.text = ""
            c == "bloquear" -> { print("> $raw", DORADO); mode = Mode.LOCKED; print("Sistema bloqueado.", DORADO) }
            c == "zoom mas" -> { print("> $raw", DORADO); setSize(textSp + 2) }
            c == "zoom menos" -> { print("> $raw", DORADO); setSize(textSp - 2) }
            c.startsWith("fuente") -> {
                print("> $raw", DORADO)
                val n = c.removePrefix("fuente").trim()
                val f = fontByName(n)
                if (f == null) print("Fuentes: terminal, serif, sans, casual, cursiva", DORADO)
                else { typeface = f; prefs.edit().putString("font", n).apply(); applyFont(); print("Fuente: $n", DORADO) }
            }
            else -> { print("> $raw", ROJO); print("Comando no reconocido. Escribe ayuda.", ROJO) }
        }
    }

    private fun showHelp() {
        print("COMANDOS DISPONIBLES", DORADO)
        print("comenzar                     pide la clave y entra", AZUL)
        print("mostrar aplicaciones         lista todas tus apps", VERDE)
        print("entrar a (app)               abre la app", ROJO)
        print("mostrar informacion de (app) datos completos de la app", AMARILLO)
        print("desinstalar (app)            desinstala la app", MORADO)
        print("ayuda                        muestra esta lista", DORADO)
        print("extras: zoom mas | zoom menos | fuente (nombre) | limpiar | bloquear", DORADO)
        print("Tip: pellizca la pantalla con dos dedos para hacer zoom.", DORADO)
    }

    private fun fontByName(n: String): Typeface? = when (n) {
        "terminal", "monospace" -> Typeface.MONOSPACE
        "serif" -> Typeface.SERIF
        "sans" -> Typeface.SANS_SERIF
        "casual" -> Typeface.create("casual", Typeface.NORMAL)
        "cursiva" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> null
    }

    // ---------- Apps ----------
    private fun apps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private fun withApp(query: String, color: Int, action: (String, String) -> Unit) {
        val q = norm(query)
        if (q.isEmpty()) { print("Falta el nombre de la app.", color); return }
        val all = apps()
        val exact = all.filter { norm(it.first) == q }
        val matches = if (exact.isNotEmpty()) exact else all.filter { norm(it.first).contains(q) }
        when {
            matches.isEmpty() -> print("No encontré ninguna app llamada \"$query\".", ROJO)
            matches.size > 1 -> {
                print("Hay varias coincidencias, sé más específico:", color)
                matches.forEach { print("- ${it.first}", color) }
            }
            else -> action(matches[0].first, matches[0].second)
        }
    }

    @Suppress("DEPRECATION")
    private fun showInfo(label: String, pkg: String) {
        val pi = packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val ai = pi.applicationInfo
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val sizeMb = File(ai.sourceDir).length() / 1024.0 / 1024.0
        val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        print("Nombre:        $label", AMARILLO)
        print("Paquete:       $pkg", AMARILLO)
        print("Versión:       ${pi.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})", AMARILLO)
        print("Tipo:          ${if (sys) "de sistema" else "instalada por el usuario"}", AMARILLO)
        print("Instalada:     ${fmt.format(Date(pi.firstInstallTime))}", AMARILLO)
        print("Actualizada:   ${fmt.format(Date(pi.lastUpdateTime))}", AMARILLO)
        print("Tamaño APK:    ${"%.1f".format(sizeMb)} MB", AMARILLO)
        print("SDK objetivo:  ${ai.targetSdkVersion}", AMARILLO)
        val perms = pi.requestedPermissions?.map { it.substringAfterLast('.') } ?: emptyList()
        print("Permisos (${perms.size}):", AMARILLO)
        perms.forEach { print("  - $it", AMARILLO) }
    }
}
