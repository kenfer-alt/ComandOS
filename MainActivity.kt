package com.comandos.os

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : Activity() {

    private enum class Mode { SETUP, SETUP_CONFIRM, LOCKED, LOGIN, READY, CONFIRM, APP_PASS }

    // Colores de cada comando
    private val AZUL = Color.parseColor("#4DA3FF")      // comenzar
    private val VERDE = Color.parseColor("#39FF14")     // mostrar aplicaciones
    private val ROJO = Color.parseColor("#FF4444")      // entrar a
    private val AMARILLO = Color.parseColor("#FFE600")  // informacion, contrasenas, carpetas
    private val MORADO = Color.parseColor("#B266FF")    // desinstalar
    private val DORADO = Color.parseColor("#FFD700")    // ayuda
    private val NARANJA = Color.parseColor("#FF9100")   // apps en plano 2
    private val CIAN = Color.parseColor("#00E5FF")      // quitar (app) del plano 2
    private val ROSA = Color.parseColor("#FF4FA3")      // quitar todo del plano 2
    private val BLANCO = Color.parseColor("#CCCCCC")

    // Tiempo que cuenta una app como "plano 2" (apps usadas hace menos de esto)
    private val WINDOW_MS = 6L * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences
    private lateinit var header: TextView
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var prompt: TextView
    private lateinit var input: EditText
    private lateinit var scale: ScaleGestureDetector

    private var mode = Mode.LOCKED
    private var masked = false
    private var pendingPass = ""
    private var pendingAction: (() -> Unit)? = null
    private var pendingPkg = ""
    private var pendingLabel = ""
    private var textSp = 15f
    private var typeface: Typeface = Typeface.MONOSPACE

    private var log = SpannableStringBuilder()
    private var cur = SpannableStringBuilder()

    private var downX = 0f
    private var downY = 0f
    private var tapOk = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (mode != Mode.SETUP && mode != Mode.SETUP_CONFIRM) {
                mode = Mode.LOCKED
                pendingAction = null
                setMasked(false)
            }
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
            print("Crea una clave segura (mínimo 6 caracteres):", DORADO)
            setMasked(true)
        } else {
            mode = Mode.LOCKED
        }
        flush()
    }

    override fun onResume() { super.onResume(); input.post { focusInput() } }

    override fun onDestroy() { super.onDestroy(); unregisterReceiver(screenOff) }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); focusInput() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* Un launcher no debe cerrarse */ }

    // Un toque en cualquier parte de la pantalla activa el teclado
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scale.onTouchEvent(ev)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; tapOk = true }
            MotionEvent.ACTION_POINTER_DOWN -> tapOk = false
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) tapOk = false
            MotionEvent.ACTION_UP -> if (tapOk && !scale.isInProgress) focusInput()
        }
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
            text = "Escribe : Ayuda"
            setTextColor(DORADO)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prompt = TextView(this).apply { text = "comandOS $ "; setTextColor(VERDE) }
        input = EditText(this).apply {
            background = null
            setTextColor(Color.WHITE)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ -> submit(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { colorInput() }
            })
        }
        row.addView(prompt)
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        scroll = ScrollView(this)
        out = TextView(this).apply { setTextColor(BLANCO) }
        scroll.addView(out)

        root.addView(header)
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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

    private fun setMasked(m: Boolean) {
        masked = m
        input.inputType = InputType.TYPE_CLASS_TEXT or
            if (m) InputType.TYPE_TEXT_VARIATION_PASSWORD
            else (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input.typeface = typeface
        prompt.setTextColor(if (m) ROJO else VERDE)
        colorInput()
        focusInput()
    }

    private fun colorFor(c: String): Int = when {
        c.startsWith("comenzar") -> AZUL
        c.startsWith("mostrar todas") -> AMARILLO
        c.startsWith("mostrar i") -> AMARILLO
        c.startsWith("mostrar") -> VERDE
        c.startsWith("entrar a la carpeta") -> AMARILLO
        c.startsWith("entrar") -> ROJO
        c.startsWith("desinstalar") -> MORADO
        c.startsWith("ayuda") -> DORADO
        c.startsWith("apps en plano") -> NARANJA
        c.startsWith("quitar todo") -> ROSA
        c.startsWith("quitar") -> CIAN
        c.startsWith("contrasena") || c.startsWith("eliminar") || c.startsWith("carpeta") ||
            c.startsWith("cambiar nombre") || c.startsWith("sacar") -> AMARILLO
        else -> Color.WHITE
    }

    private fun colorInput() {
        if (!::input.isInitialized) return
        input.setTextColor(if (masked) Color.WHITE else colorFor(norm(input.text.toString())))
    }

    private fun focusInput() {
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
    }

    // Cada comando forma un bloque; el bloque nuevo se pone arriba, debajo del prompt
    private fun print(text: String, color: Int = BLANCO) {
        val s = SpannableString(text + "\n")
        s.setSpan(ForegroundColorSpan(color), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cur.append(s)
    }

    private fun flush() {
        if (cur.isEmpty()) return
        cur.append("\n")
        log.insert(0, cur)
        cur = SpannableStringBuilder()
        if (log.length > 30000) log.delete(30000, log.length)
        out.text = log
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun echo(raw: String, color: Int) = print("comandOS \$ $raw", color)

    // ---------- Clave del sistema ----------
    private fun hash(pass: String, salt: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest((salt + pass).toByteArray()), Base64.NO_WRAP)

    private fun newSalt(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private fun savePass(pass: String) {
        val salt = newSalt()
        prefs.edit().putString("salt", salt).putString("hash", hash(pass, salt)).apply()
    }

    private fun checkPass(pass: String): Boolean {
        val salt = prefs.getString("salt", "") ?: ""
        return hash(pass, salt) == prefs.getString("hash", null)
    }

    // ---------- Contraseñas de apps ----------
    private fun hasAppLock(pkg: String): Boolean = prefs.contains("lock_$pkg")

    private fun setAppLock(pkg: String, pass: String) {
        val salt = newSalt()
        prefs.edit().putString("lock_$pkg", salt + "|" + hash(pass, salt)).apply()
    }

    private fun removeAppLock(pkg: String) {
        prefs.edit().remove("lock_$pkg").apply()
    }

    private fun checkAppLock(pkg: String, pass: String): Boolean {
        val v = prefs.getString("lock_$pkg", null) ?: return true
        val i = v.indexOf('|')
        if (i < 0) return false
        return hash(pass, v.substring(0, i)) == v.substring(i + 1)
    }

    // Pide la clave del sistema y, si es correcta, ejecuta la acción
    private fun askSystemPass(action: () -> Unit) {
        pendingAction = action
        mode = Mode.CONFIRM
        print("Escribe la clave del sistema para confirmar:", AMARILLO)
        setMasked(true)
    }

    // ---------- Carpetas ----------
    private fun loadFolders(): LinkedHashMap<String, MutableList<String>> {
        val res = LinkedHashMap<String, MutableList<String>>()
        try {
            val o = JSONObject(prefs.getString("folders", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = o.getJSONArray(k)
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                res[k] = list
            }
        } catch (e: Exception) {
        }
        return res
    }

    private fun saveFolders(m: Map<String, List<String>>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray(v))
        prefs.edit().putString("folders", o.toString()).apply()
    }

    // Apps (nombre, paquete) de una carpeta que siguen instaladas
    private fun folderApps(pkgs: List<String>): List<Pair<String, String>> {
        val m = apps().associate { it.second to it.first }
        return pkgs.mapNotNull { p -> m[p]?.let { it to p } }
    }

    private fun folderOrPrint(query: String, folders: Map<String, List<String>>): String? {
        val n = norm(query)
        if (n.isEmpty()) { print("Falta el nombre de la carpeta.", ROJO); return null }
        val exact = folders.keys.filter { norm(it) == n }
        val m = if (exact.isNotEmpty()) exact else folders.keys.filter { norm(it).contains(n) }
        return when {
            m.isEmpty() -> { print("No existe la carpeta \"$query\".", ROJO); null }
            m.size > 1 -> {
                print("Hay varias carpetas parecidas, sé más específico:", ROJO)
                m.forEach { print("- $it", ROJO) }
                null
            }
            else -> m[0]
        }
    }

    private fun createFolder(text: String) {
        val all = apps()
        val found = LinkedHashMap<String, String>() // paquete -> nombre
        val missing = ArrayList<String>()

        fun tryName(n: String): Boolean {
            val m = findApps(n, all)
            if (m.size == 1) { found[m[0].second] = m[0].first; return true }
            return false
        }

        for (tok in text.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (tryName(tok)) continue
            val parts = tok.split(" y ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                for (p in parts) if (!tryName(p)) missing.add(p)
            } else {
                missing.add(tok)
            }
        }

        if (found.isEmpty()) {
            print("No encontré ninguna de esas apps.", ROJO)
            return
        }

        val folders = loadFolders()
        // Si una app ya estaba en otra carpeta, se mueve a la nueva
        for (list in folders.values) list.removeAll(found.keys)

        var name = ""
        do {
            name = "Carpeta " + (100..999).random()
        } while (folders.keys.any { norm(it) == norm(name) })

        folders[name] = ArrayList(found.keys)
        saveFolders(folders)

        print("Nombre de la carpeta: $name", AMARILLO)
        print("Apps dentro: " + found.values.joinToString(", "), AMARILLO)
        if (missing.isNotEmpty()) print("No encontré: " + missing.joinToString(", "), ROJO)
    }

    // ---------- Entrada ----------
    private fun submit() {
        val raw = input.text.toString().trim()
        input.setText("")
        if (raw.isEmpty()) return
        handle(raw)
        flush()
    }

    private fun handle(raw: String) {
        when (mode) {
            Mode.SETUP -> {
                echo("*".repeat(raw.length), ROJO)
                if (raw.length < 6) { print("Muy corta. Usa mínimo 6 caracteres.", ROJO); return }
                pendingPass = raw
                mode = Mode.SETUP_CONFIRM
                print("Repite la clave para confirmar:", DORADO)
            }
            Mode.SETUP_CONFIRM -> {
                echo("*".repeat(raw.length), ROJO)
                if (raw == pendingPass) {
                    savePass(raw); pendingPass = ""
                    mode = Mode.READY; setMasked(false)
                    print("Clave guardada.", VERDE)
                } else {
                    mode = Mode.SETUP; pendingPass = ""
                    print("No coinciden. Crea la clave de nuevo:", ROJO)
                }
            }
            Mode.LOGIN -> {
                echo("*".repeat(raw.length), ROJO)
                if (checkPass(raw)) {
                    mode = Mode.READY; setMasked(false)
                    print("Acceso concedido.", VERDE)
                } else {
                    mode = Mode.LOCKED; setMasked(false)
                    print("Clave incorrecta.", ROJO)
                }
            }
            Mode.CONFIRM -> {
                echo("*".repeat(raw.length), AMARILLO)
                val act = pendingAction
                pendingAction = null
                mode = Mode.READY; setMasked(false)
                if (checkPass(raw)) act?.invoke()
                else print("Clave del sistema incorrecta. Cancelado.", ROJO)
            }
            Mode.APP_PASS -> {
                echo("*".repeat(raw.length), ROJO)
                val pkg = pendingPkg
                val label = pendingLabel
                mode = Mode.READY; setMasked(false)
                if (checkAppLock(pkg, raw)) launchApp(label, pkg)
                else print("Contraseña incorrecta.", ROJO)
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
                echo(raw, AZUL)
                print("Escribe tu clave:", AZUL)
                mode = Mode.LOGIN; setMasked(true)
            }
            "ayuda" -> { echo(raw, DORADO); showHelp() }
            else -> { echo(raw, ROJO); print("Sistema bloqueado. Escribe comenzar.", ROJO) }
        }
    }

    private fun launchApp(label: String, pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) print("No se puede abrir $label.", ROJO)
        else { print("Abriendo $label...", ROJO); startActivity(i) }
    }

    private fun runCommand(raw: String) {
        val c = norm(raw)
        when {
            c == "comenzar" -> { echo(raw, AZUL); print("Ya estás dentro del sistema.", AZUL) }

            c == "mostrar aplicaciones" -> {
                echo(raw, VERDE)
                val inFolders = loadFolders().values.flatten().toSet()
                val all = apps()
                val list = all.filter { it.second !in inFolders }
                list.forEachIndexed { i, a -> print("${i + 1}. ${a.first}", VERDE) }
                print("Total: ${list.size} aplicaciones", VERDE)
                val hidden = all.size - list.size
                if (hidden > 0) print("($hidden más están en carpetas)", VERDE)
            }

            // ----- Contraseñas de apps -----
            c.startsWith("contrasena para ") -> {
                val m = Regex("^contrase[ñn]a\\s+para\\s+(.+?)\\s+es\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw.trim())
                if (m == null) {
                    echo(raw, AMARILLO)
                    print("Formato: contraseña para (app) es (clave)", AMARILLO)
                } else {
                    val appName = m.groupValues[1]
                    val pass = m.groupValues[2]
                    echo("contraseña para $appName es ****", AMARILLO)
                    withApp(appName, AMARILLO) { label, pkg ->
                        askSystemPass {
                            setAppLock(pkg, pass)
                            print("Contraseña guardada para $label.", AMARILLO)
                        }
                    }
                }
            }

            c.startsWith("eliminar contrasena de ") -> {
                echo(raw, AMARILLO)
                withApp(c.removePrefix("eliminar contrasena de "), AMARILLO) { label, pkg ->
                    if (!hasAppLock(pkg)) print("$label no tiene contraseña.", AMARILLO)
                    else askSystemPass {
                        removeAppLock(pkg)
                        print("Contraseña de $label eliminada.", AMARILLO)
                    }
                }
            }

            // ----- Carpetas -----
            c.startsWith("carpeta para ") -> {
                echo(raw, AMARILLO)
                createFolder(c.removePrefix("carpeta para "))
            }

            c.startsWith("cambiar nombre de la carpeta ") -> {
                echo(raw, AMARILLO)
                val m = Regex("^cambiar\\s+nombre\\s+de\\s+la\\s+carpeta\\s+(.+?)\\s+a\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw.trim())
                if (m == null) {
                    print("Formato: cambiar nombre de la carpeta (nombre) a (nuevo nombre)", AMARILLO)
                } else {
                    val folders = loadFolders()
                    val key = folderOrPrint(m.groupValues[1], folders)
                    if (key != null) {
                        val newName = m.groupValues[2].trim()
                        if (folders.keys.any { it != key && norm(it) == norm(newName) }) {
                            print("Ya existe una carpeta con ese nombre.", ROJO)
                        } else {
                            val rebuilt = LinkedHashMap<String, MutableList<String>>()
                            for ((k, v) in folders) rebuilt[if (k == key) newName else k] = v
                            saveFolders(rebuilt)
                            print("La carpeta \"$key\" ahora se llama \"$newName\".", AMARILLO)
                        }
                    }
                }
            }

            c.startsWith("entrar a la carpeta ") -> {
                echo(raw, AMARILLO)
                val folders = loadFolders()
                val key = folderOrPrint(c.removePrefix("entrar a la carpeta "), folders)
                if (key != null) {
                    val inside = folderApps(folders[key] ?: mutableListOf())
                    print("Carpeta: $key", AMARILLO)
                    if (inside.isEmpty()) print("(vacía)", AMARILLO)
                    else inside.forEachIndexed { i, a -> print("${i + 1}. ${a.first}", AMARILLO) }
                }
            }

            c.startsWith("eliminar carpeta ") -> {
                echo(raw, AMARILLO)
                val folders = loadFolders()
                val key = folderOrPrint(c.removePrefix("eliminar carpeta "), folders)
                if (key != null) {
                    val n = folders[key]?.size ?: 0
                    folders.remove(key)
                    saveFolders(folders)
                    print("Carpeta \"$key\" eliminada. $n apps volvieron a su lugar.", AMARILLO)
                }
            }

            c.startsWith("sacar ") && c.contains(" de la carpeta ") -> {
                echo(raw, AMARILLO)
                val rest = c.removePrefix("sacar ")
                val idx = rest.indexOf(" de la carpeta ")
                val appName = rest.substring(0, idx)
                val folderName = rest.substring(idx + " de la carpeta ".length)
                val folders = loadFolders()
                val key = folderOrPrint(folderName, folders)
                if (key != null) {
                    val inside = folderApps(folders[key] ?: mutableListOf())
                    withApp(appName, AMARILLO, inside, "no está en la carpeta \"$key\"") { label, pkg ->
                        folders[key]?.remove(pkg)
                        saveFolders(folders)
                        print("$label salió de la carpeta \"$key\".", AMARILLO)
                    }
                }
            }

            c == "mostrar todas las carpetas" -> {
                echo(raw, AMARILLO)
                val folders = loadFolders()
                if (folders.isEmpty()) print("No tienes carpetas.", AMARILLO)
                else {
                    var n = 0
                    for ((name, pkgs) in folders) {
                        n++
                        print("$n. $name  (${folderApps(pkgs).size} apps)", AMARILLO)
                    }
                }
            }

            // ----- Apps -----
            c.startsWith("entrar a ") || c.startsWith("entrar ") -> {
                echo(raw, ROJO)
                val name = c.removePrefix("entrar a ").removePrefix("entrar ")
                withApp(name, ROJO) { label, pkg ->
                    if (hasAppLock(pkg)) {
                        pendingPkg = pkg
                        pendingLabel = label
                        mode = Mode.APP_PASS
                        print("$label está protegida. Escribe su contraseña:", ROJO)
                        setMasked(true)
                    } else {
                        launchApp(label, pkg)
                    }
                }
            }

            c.startsWith("mostrar informacion de ") -> {
                echo(raw, AMARILLO)
                withApp(c.removePrefix("mostrar informacion de "), AMARILLO) { label, pkg -> showInfo(label, pkg) }
            }

            c.startsWith("desinstalar ") -> {
                echo(raw, MORADO)
                withApp(c.removePrefix("desinstalar "), MORADO) { label, pkg ->
                    print("Solicitando desinstalar $label (Android te pedirá confirmar)...", MORADO)
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }

            // ----- Plano 2 -----
            c == "apps en plano 2" -> {
                echo(raw, NARANJA)
                val list = recentOrNull(NARANJA)
                if (list != null) {
                    if (list.isEmpty()) print("No hay apps en plano 2.", NARANJA)
                    else {
                        list.forEachIndexed { i, a -> print("${i + 1}. ${a.first}  (${ago(a.third)})", NARANJA) }
                        print("Total: ${list.size} apps", NARANJA)
                    }
                }
            }

            c == "quitar todo del plano 2" -> {
                echo(raw, ROSA)
                val list = recentOrNull(ROSA)
                if (list != null) {
                    if (list.isEmpty()) print("No hay apps en plano 2.", ROSA)
                    else {
                        list.forEach { kill(it.second) }
                        print("Quitadas ${list.size} apps del plano 2.", ROSA)
                    }
                }
            }

            c.startsWith("quitar ") && c.endsWith(" del plano 2") -> {
                echo(raw, CIAN)
                val name = c.removePrefix("quitar ").removeSuffix(" del plano 2").trim()
                val list = recentOrNull(CIAN)
                if (list != null) {
                    withApp(name, CIAN, list.map { it.first to it.second }, "no está en plano 2") { label, pkg ->
                        kill(pkg)
                        print("$label quitada del plano 2.", CIAN)
                    }
                }
            }

            // ----- Sistema -----
            c == "ayuda" -> { echo(raw, DORADO); showHelp() }
            c == "limpiar" -> { log = SpannableStringBuilder(); out.text = "" }
            c == "bloquear" -> { echo(raw, DORADO); mode = Mode.LOCKED; print("Sistema bloqueado.", DORADO) }
            c == "zoom mas" -> { echo(raw, DORADO); setSize(textSp + 2) }
            c == "zoom menos" -> { echo(raw, DORADO); setSize(textSp - 2) }
            c.startsWith("fuente") -> {
                echo(raw, DORADO)
                val n = c.removePrefix("fuente").trim()
                val f = fontByName(n)
                if (f == null) print("Fuentes: terminal, serif, sans, casual, cursiva", DORADO)
                else { typeface = f; prefs.edit().putString("font", n).apply(); applyFont(); print("Fuente: $n", DORADO) }
            }
            else -> { echo(raw, ROJO); print("Comando no reconocido. Escribe ayuda.", ROJO) }
        }
    }

    private fun showHelp() {
        print("COMANDOS DISPONIBLES", DORADO)
        print("comenzar                      pide la clave y entra", AZUL)
        print("mostrar aplicaciones          lista tus apps (sin las de carpetas)", VERDE)
        print("entrar a (app)                abre la app", ROJO)
        print("mostrar informacion de (app)  datos completos de la app", AMARILLO)
        print("desinstalar (app)             desinstala la app", MORADO)
        print("apps en plano 2               apps usadas hace poco", NARANJA)
        print("quitar (app) del plano 2      cierra esa app", CIAN)
        print("quitar todo del plano 2       cierra todas", ROSA)
        print("contraseña para (app) es (clave)   protege una app", AMARILLO)
        print("eliminar contraseña de (app)  quita esa protección", AMARILLO)
        print("carpeta para (app, app y app) crea una carpeta", AMARILLO)
        print("cambiar nombre de la carpeta (nombre) a (nuevo)", AMARILLO)
        print("entrar a la carpeta (nombre)  muestra sus apps", AMARILLO)
        print("eliminar carpeta (nombre)     borra la carpeta", AMARILLO)
        print("sacar (app) de la carpeta (nombre)  saca una app", AMARILLO)
        print("mostrar todas las carpetas    lista las carpetas", AMARILLO)
        print("ayuda                         muestra esta lista", DORADO)
        print("extras: zoom mas | zoom menos | fuente (nombre) | limpiar | bloquear", DORADO)
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

    private fun findApps(query: String, pool: List<Pair<String, String>>): List<Pair<String, String>> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val exact = pool.filter { norm(it.first) == q }
        return if (exact.isNotEmpty()) exact else pool.filter { norm(it.first).contains(q) }
    }

    private fun withApp(
        query: String,
        color: Int,
        pool: List<Pair<String, String>> = apps(),
        notFound: String? = null,
        action: (String, String) -> Unit
    ) {
        if (norm(query).isEmpty()) { print("Falta el nombre de la app.", color); return }
        val matches = findApps(query, pool)
        when {
            matches.isEmpty() ->
                if (notFound != null) print("\"$query\" $notFound.", ROJO)
                else print("No encontré ninguna app llamada \"$query\".", ROJO)
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
        print("Versión:       ${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})", AMARILLO)
        print("Tipo:          ${if (sys) "de sistema" else "instalada por el usuario"}", AMARILLO)
        print("Instalada:     ${fmt.format(Date(pi.firstInstallTime))}", AMARILLO)
        print("Actualizada:   ${fmt.format(Date(pi.lastUpdateTime))}", AMARILLO)
        print("Tamaño APK:    ${"%.1f".format(sizeMb)} MB", AMARILLO)
        print("SDK objetivo:  ${ai.targetSdkVersion}", AMARILLO)
        val perms = pi.requestedPermissions?.map { it.substringAfterLast('.') } ?: emptyList()
        print("Permisos (${perms.size}):", AMARILLO)
        perms.forEach { print("  - $it", AMARILLO) }
    }

    // ---------- Plano 2 ----------
    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val m = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return m == AppOpsManager.MODE_ALLOWED
    }

    // Devuelve la lista del plano 2, o null si falta el permiso (y abre la pantalla para darlo)
    private fun recentOrNull(color: Int): List<Triple<String, String, Long>>? {
        if (!hasUsageAccess()) {
            print("Falta el permiso de acceso de uso.", color)
            print("Activa comandOS en la pantalla que se abre, regresa y repite el comando.", color)
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) {}
            return null
        }
        return recentApps()
    }

    @Suppress("DEPRECATION")
    private fun recentApps(): List<Triple<String, String, Long>> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - WINDOW_MS, now)
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                last[e.packageName] = e.timeStamp
            }
        }
        val launchable = apps().associate { it.second to it.first }
        return last.entries
            .filter { it.key != packageName && launchable.containsKey(it.key) }
            .sortedByDescending { it.value }
            .map { Triple(launchable[it.key] ?: it.key, it.key, it.value) }
    }

    private fun kill(pkg: String) {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60000
        return if (m < 1) "hace un momento" else if (m < 60) "hace $m min" else "hace ${m / 60} h"
    }
}
