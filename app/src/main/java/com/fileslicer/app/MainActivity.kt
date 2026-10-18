package com.fileslicer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Muted = Color(0xFF94A3B8)
private val CardBg = Color(0xFF1E293B)
private val Accent = Color(0xFF818CF8)
private val Good = Color(0xFF4ADE80)
private val Bad = Color(0xFFF87171)

data class Picked(val uri: Uri, val name: String, val size: Long)

class MainActivity : ComponentActivity() {

    private var tick by mutableStateOf(0)
    private var picked by mutableStateOf<Picked?>(null)
    private var pendingResume: Entry? = null
    private var pendingPw: String = ""

    // split options (kept between launches)
    private var optTemplate by mutableStateOf(DEFAULT_TEMPLATE)
    private var optSuffix by mutableStateOf(DEFAULT_SUFFIX)
    private var optMode by mutableStateOf("size")
    private var optMb by mutableStateOf("10")
    private var optCount by mutableStateOf("5")
    private var optZip by mutableStateOf(false)
    private var optEnc by mutableStateOf(false)
    private var optPw by mutableStateOf("")

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val legacyPerm =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadOpts()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    // ---------- helpers ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun loadOpts() {
        val sp = getSharedPreferences("opts", MODE_PRIVATE)
        optTemplate = sp.getString("template", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        optSuffix = sp.getString("suffix", DEFAULT_SUFFIX) ?: DEFAULT_SUFFIX
        optMode = sp.getString("mode", "size") ?: "size"
        optMb = sp.getString("mb", "10") ?: "10"
        optCount = sp.getString("count", "5") ?: "5"
        optZip = sp.getBoolean("zip", false)
        optEnc = sp.getBoolean("enc", false)
    }

    private fun saveOpts() {
        getSharedPreferences("opts", MODE_PRIVATE).edit()
            .putString("template", optTemplate)
            .putString("suffix", optSuffix)
            .putString("mode", optMode)
            .putString("mb", optMb)
            .putString("count", optCount)
            .putBoolean("zip", optZip)
            .putBoolean("enc", optEnc)
            .apply()
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPerm.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun ignoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings > Battery and allow FileSlicer to run unrestricted")
            }
        }
    }

    private fun meta(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
        }
        if (size < 0) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            } catch (e: Exception) {
            }
        }
        return Pair(name, size)
    }

    private fun onPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val (name, size) = meta(uri)
        val pr = pendingResume
        if (pr != null) {
            pendingResume = null
            if (size == pr.info.size) {
                begin(pr.info.toJob(pr.dir, uri.toString(), pendingPw))
            } else {
                toast("That file's size doesn't match the original (${fmtSize(pr.info.size)}) — not resuming")
            }
        } else {
            picked = Picked(uri, name, size)
        }
    }

    private fun begin(job: Job) {
        if (!hasStorage()) {
            askStorage()
            toast("Allow file access first, then try again")
            return
        }
        JobState.flow.value = UiJob(running = true, kind = job.kind, title = job.title())
        SplitService.start(this, job)
    }

    private fun resumeEntry(e: Entry, pw: String) {
        val info = e.info
        val stageOk = info.stageReady && File(e.dir, Cfg.STAGE).isFile
        if (info.enc && !stageOk && pw.isEmpty()) {
            toast("Enter the password in the Split tab first, then tap Resume")
            return
        }
        if (stageOk) {
            begin(info.toJob(e.dir, info.source, pw))
            return
        }
        val ok = try {
            contentResolver.openInputStream(Uri.parse(info.source))?.use { true } ?: false
        } catch (ex: Exception) {
            false
        }
        if (ok) {
            begin(info.toJob(e.dir, info.source, pw))
        } else {
            pendingResume = e
            pendingPw = pw
            toast("The link to the original file expired — pick the same file again")
            picker.launch(arrayOf("*/*"))
        }
    }

    // ---------- UI ----------
    @Composable
    private fun Root() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                onPrimary = Color(0xFF0F172A),
                secondary = Color(0xFF38BDF8),
                background = Color(0xFF0F172A),
                onBackground = Color(0xFFE2E8F0),
                surface = Color(0xFF0F172A),
                onSurface = Color(0xFFE2E8F0)
            )
        ) {
            val job by JobState.flow.collectAsState()
            val ok = remember(tick) { hasStorage() }
            val battOk = remember(tick) { ignoringBattery() }
            var tab by remember { mutableStateOf(0) }
            var entries by remember { mutableStateOf(listOf<Entry>()) }

            LaunchedEffect(tick, job.running, ok) {
                if (ok) entries = withContext(Dispatchers.IO) { Engine.scanDownloads() }
            }

            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("FileSlicer", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Accent)
                    Text(
                        "Split any file into disguised pieces — optionally zipped and encrypted — and merge them back.",
                        color = Muted
                    )

                    if (!ok) {
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("File access needed", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "FileSlicer saves to /sdcard/Download, so Android needs your permission.",
                                    color = Muted
                                )
                                Button(onClick = { askStorage() }) { Text("Allow file access") }
                            }
                        }
                    }

                    JobCard(job)

                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = Color.Transparent,
                        contentColor = Accent
                    ) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Split") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Merge") })
                    }

                    if (tab == 0) SplitTab(job, entries, ok) else MergeTab(job, entries, ok)

                    if (!battOk) {
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Keep it alive in the background", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Some phones (Xiaomi, Oppo, Samsung…) kill background apps. Allow unrestricted battery use for a steadier job. Anything interrupted can still be resumed.",
                                    color = Muted
                                )
                                Button(onClick = { askBattery() }) { Text("Allow background use") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                if (sub != null) Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun ModeBtn(label: String, selected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) Accent else Color(0xFF334155),
                contentColor = if (selected) Color(0xFF0F172A) else Color.White
            )
        ) { Text(label) }
    }

    @Composable
    private fun JobCard(job: UiJob) {
        if (job.title.isEmpty()) return
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(job.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (job.running && job.phase.isNotEmpty()) Text(job.phase, color = Accent)
                if (job.running || job.total > 0) {
                    val frac = if (job.total > 0) (job.done.toFloat() / job.total.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                    val speedTxt = if (job.running && job.speed > 0) "  •  ${fmtSize(job.speed)}/s" else ""
                    Text(
                        "${(frac * 100).toInt()}%  •  ${fmtSize(job.done)} / ${fmtSize(job.total)}$speedTxt",
                        color = Muted
                    )
                }
                if (job.result.isNotEmpty()) {
                    Text(job.result, color = if (job.error) Bad else Good)
                }
                if (job.running) {
                    Button(
                        onClick = { Control.cancel = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)
                    ) { Text("Stop") }
                }
            }
        }
    }

    @Composable
    private fun EntryRow(e: Entry, label: String, busy: Boolean, onClick: () -> Unit) {
        val i = e.info
        val tags = (if (i.zip) " • zip" else "") + (if (i.enc) " • encrypted" else "")
        val txt = if (i.status == "DONE") {
            "${i.parts} parts • ${fmtSize(i.streamSize)}$tags"
        } else if (i.parts <= 0) {
            "Incomplete: still preparing$tags"
        } else {
            "Incomplete: ${e.done}/${i.parts} parts$tags"
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(i.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(txt, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onClick, enabled = !busy) { Text(label) }
            }
        }
    }

    @Composable
    private fun SplitTab(job: UiJob, entries: List<Entry>, ok: Boolean) {
        val p = picked
        val sizeBytes = ((optMb.toDoubleOrNull() ?: 0.0) * 1048576.0).toLong()
        val cnt = optCount.toIntOrNull() ?: 0
        val valid = if (optMode == "count") cnt in 1..100000 else sizeBytes >= 1024
        val streamEst = p?.size ?: 0L
        val estParts = if (!valid || streamEst <= 0L) 0
        else Engine.partCount(streamEst, Engine.resolvePartSize(streamEst, optMode, sizeBytes, cnt))
        val example = Engine.partName(
            p?.name ?: "movie.mkv", optTemplate, optSuffix, if (estParts > 0) estParts else 12, 1
        )
        val transforms = optZip || optEnc
        val pwOk = !optEnc || optPw.isNotEmpty()

        // 1. file
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. File", fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = {
                        pendingResume = null
                        picker.launch(arrayOf("*/*"))
                    },
                    enabled = !job.running
                ) { Text("Choose file to split") }
                if (p != null) {
                    Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fmtSize(p.size), color = Muted)
                    Text("Saves to /sdcard/Download/${Engine.safeName(p.name)}/", color = Muted)
                }
            }
        }

        // 2. options
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. Options", fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = optTemplate,
                    onValueChange = { optTemplate = it },
                    singleLine = true,
                    label = { Text("Part name style") },
                    supportingText = {
                        Text(
                            if (optTemplate.contains("{n}")) "Tokens: {name} {base} {ext} {n} {total}"
                            else "No {n} in the style — \".{n}\" will be added"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = optSuffix,
                    onValueChange = { optSuffix = it },
                    singleLine = true,
                    label = { Text("Fake suffix (.pdf, .html, .txt … or empty)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Example: $example", color = Muted, style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeBtn("By part size", optMode == "size") { optMode = "size" }
                    ModeBtn("By number of parts", optMode == "count") { optMode = "count" }
                }
                if (optMode == "size") {
                    OutlinedTextField(
                        value = optMb,
                        onValueChange = { optMb = it },
                        singleLine = true,
                        label = { Text("Part size (MB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = optCount,
                        onValueChange = { optCount = it },
                        singleLine = true,
                        label = { Text("Number of parts") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!valid) {
                    Text(
                        if (optMode == "count") "Enter a number of parts from 1 to 100000"
                        else "Part size must be at least 0.001 MB",
                        color = Bad
                    )
                } else if (p != null && estParts > 0) {
                    Text(
                        (if (transforms) "≈ " else "") + "$estParts parts" +
                            (if (transforms) " (counted after zip/encrypt)" else ""),
                        color = Muted
                    )
                }

                ToggleRow(
                    "Compress to ZIP first",
                    "Merge unzips it again automatically",
                    optZip
                ) { optZip = it }
                ToggleRow(
                    "Encrypt into real text files",
                    "AES-256 + Base64: every part opens as plain text",
                    optEnc
                ) {
                    optEnc = it
                    if (it && optSuffix == DEFAULT_SUFFIX) optSuffix = ".txt"
                    if (!it && optSuffix == ".txt") optSuffix = DEFAULT_SUFFIX
                }
                if (optEnc) {
                    OutlinedTextField(
                        value = optPw,
                        onValueChange = { optPw = it },
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Not stored anywhere. Forget it and the data is unrecoverable.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (transforms) {
                    Text(
                        "Zip/encrypt needs temporary extra space in the folder while preparing.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        if (p != null) {
                            saveOpts()
                            begin(
                                Job(
                                    kind = "split",
                                    uri = p.uri.toString(),
                                    name = p.name,
                                    size = p.size,
                                    template = optTemplate,
                                    suffix = optSuffix,
                                    mode = optMode,
                                    partSize = if (optMode == "size") sizeBytes else 0L,
                                    count = if (optMode == "count") cnt else 0,
                                    zip = optZip,
                                    enc = optEnc,
                                    password = if (optEnc) optPw else ""
                                )
                            )
                        }
                    },
                    enabled = !job.running && p != null && p.size > 0 && ok && valid && pwOk
                ) { Text("Start splitting") }
            }
        }

        val unfinished = entries.filter { it.info.status != "DONE" }
        if (unfinished.isNotEmpty()) {
            Text("Unfinished splits", fontWeight = FontWeight.SemiBold)
            unfinished.forEach { e ->
                EntryRow(e, "Resume", job.running) { resumeEntry(e, optPw) }
            }
        }
    }

    @Composable
    private fun MergeTab(job: UiJob, entries: List<Entry>, ok: Boolean) {
        var path by remember { mutableStateOf("/sdcard/Download/") }
        var check by remember { mutableStateOf<Check?>(null) }
        var outName by remember { mutableStateOf("") }
        var mPw by remember { mutableStateOf("") }
        var mSuffix by remember { mutableStateOf(optSuffix) }
        var mZip by remember { mutableStateOf(false) }

        fun doCheck(p: String) {
            val c = Engine.inspect(File(p.trim().trimEnd('/')), mSuffix)
            check = c
            outName = c.guess
            mZip = c.zip
        }

        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Folder that contains the splits", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = path,
                    onValueChange = {
                        path = it
                        check = null
                    },
                    singleLine = true,
                    label = { Text("Folder path") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mSuffix,
                    onValueChange = {
                        mSuffix = it
                        check = null
                    },
                    singleLine = true,
                    label = { Text("Part suffix (only used if the folder has no saved info)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { doCheck(path) }, enabled = ok) { Text("Check folder") }
            }
        }

        val c = check
        if (c != null) {
            val prob = c.problem
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prob != null) {
                        Text(prob, color = Bad)
                    } else {
                        Text("${c.files.size} parts • ${fmtSize(c.bytes)}", color = Good)

                        if (c.enc) {
                            Text("Encrypted text parts — enter the password:", color = Muted)
                            OutlinedTextField(
                                value = mPw,
                                onValueChange = { mPw = it },
                                singleLine = true,
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (c.zipLocked) {
                            if (c.zip) Text("The ZIP will be unpacked automatically.", color = Muted)
                        } else {
                            ToggleRow(
                                "Unzip after merging",
                                "Turn on if the file was zipped before splitting",
                                mZip
                            ) { mZip = it }
                        }

                        if (c.info == null) {
                            Text(
                                "No saved name found in this folder. Please confirm the original file name (with extension):",
                                color = Muted
                            )
                        } else {
                            Text("Original name was remembered from the folder.", color = Muted)
                        }
                        OutlinedTextField(
                            value = outName,
                            onValueChange = { outName = it },
                            singleLine = true,
                            label = { Text("Original file name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                begin(
                                    Job(
                                        kind = "merge",
                                        folder = c.dir.path,
                                        outName = outName.trim(),
                                        suffix = mSuffix,
                                        zip = mZip,
                                        password = mPw
                                    )
                                )
                            },
                            enabled = !job.running && outName.isNotBlank() && ok && (!c.enc || mPw.isNotEmpty())
                        ) { Text("Merge into original") }
                        Text("Result goes to /sdcard/Download/Merged/", color = Muted)
                    }
                }
            }
        }

        if (entries.isNotEmpty()) {
            Text("Found in Download", fontWeight = FontWeight.SemiBold)
            entries.forEach { e ->
                EntryRow(e, "Select", false) {
                    path = e.dir.path
                    doCheck(e.dir.path)
                }
            }
        }
    }
}
