package com.fileslicer.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Cfg {
    const val INFO = ".splitinfo"                // hidden text file inside the split folder
    const val STAGE = ".stage"                   // hidden temp file (zipped / encrypted stream)
    const val MAGIC = "#FILESLICER-ENC-1"        // first line of encrypted text parts
    const val BUF = 4 * 1024 * 1024
    val downloads: File get() = File(Environment.getExternalStorageDirectory(), "Download")
    val mergedDir: File get() = File(downloads, "Merged")
}

class Cancelled : Exception()

data class Info(
    val name: String,          // original file name
    val size: Long,            // original file size
    val streamSize: Long,      // total bytes across all parts (differs from size when zipped/encrypted)
    val partSize: Long,        // resolved bytes per part (0 until known)
    val parts: Int,            // resolved number of parts (0 until known)
    val source: String,        // content:// uri of the original (for resume)
    val status: String,        // SPLITTING / STOPPED / ERROR / DONE
    val template: String,
    val suffix: String,
    val mode: String,          // "size" or "count"
    val reqSize: Long,         // requested part size (size mode)
    val reqCount: Int,         // requested number of parts (count mode)
    val zip: Boolean,
    val enc: Boolean,
    val stageReady: Boolean
)

data class Entry(val dir: File, val info: Info, val done: Int)

data class Check(
    val dir: File,
    val info: Info?,
    val files: List<File>,
    val bytes: Long,
    val guess: String,
    val problem: String?,
    val enc: Boolean,
    val zip: Boolean,
    val zipLocked: Boolean
)

fun Info.toJob(dir: File, uri: String, pw: String): Job =
    Job(
        kind = "split", uri = uri, name = name, size = size, folder = dir.path, outName = "",
        template = template, suffix = suffix, mode = mode, partSize = reqSize, count = reqCount,
        zip = zip, enc = enc, password = pw
    )

// ============================================================
//  Crypto: AES-256-GCM in 64 KiB chunks, key from PBKDF2.
//  Nonce = 4 random bytes + 8-byte chunk counter (top bit marks the LAST chunk,
//  so truncation or reordering is detected).
// ============================================================
object Crypto {
    const val CH = 64 * 1024

    fun key(pw: String, salt: ByteArray): SecretKey {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120000, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun nonce(np: ByteArray, ctr: Long, last: Boolean): ByteArray {
        val bb = ByteBuffer.allocate(12)
        bb.put(np)
        bb.putLong(if (last) (ctr or Long.MIN_VALUE) else ctr)
        return bb.array()
    }
}

class GcmEncOut(
    private val dst: OutputStream,
    private val key: SecretKey,
    private val np: ByteArray
) : OutputStream() {
    private val buf = ByteArray(Crypto.CH)
    private var pos = 0
    private var ctr = 0L
    private var closed = false
    private val c = Cipher.getInstance("AES/GCM/NoPadding")

    private fun flushChunk(last: Boolean) {
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, Crypto.nonce(np, ctr, last)))
        dst.write(c.doFinal(buf, 0, pos))
        pos = 0
        ctr++
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            if (pos == buf.size) flushChunk(false)
            val n = minOf(l, buf.size - pos)
            System.arraycopy(b, o, buf, pos, n)
            pos += n
            o += n
            l -= n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        flushChunk(true)
        dst.close()
    }
}

class GcmDecIn(
    private val src: InputStream,
    private val key: SecretKey,
    private val np: ByteArray
) : InputStream() {
    private var buf = ByteArray(0)
    private var pos = 0
    private var pending: ByteArray? = null
    private var started = false
    private var ctr = 0L
    private var eof = false
    private val c = Cipher.getInstance("AES/GCM/NoPadding")

    private fun readChunk(): ByteArray? {
        val b = ByteArray(Crypto.CH + 16)
        var n = 0
        while (n < b.size) {
            val r = src.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return if (n == 0) null else if (n == b.size) b else b.copyOf(n)
    }

    private fun fill(): Boolean {
        if (eof) return false
        if (!started) {
            pending = readChunk()
            started = true
        }
        val cur = pending
        if (cur == null) {
            eof = true
            return false
        }
        val nxt = readChunk()
        val last = nxt == null
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Crypto.nonce(np, ctr, last)))
        buf = try {
            c.doFinal(cur)
        } catch (e: java.security.GeneralSecurityException) {
            throw IOException("Wrong password, or the text parts were edited / corrupted / incomplete")
        }
        pos = 0
        pending = nxt
        ctr++
        if (last) eof = true
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else (one[0].toInt() and 0xff)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buf.size) {
            if (!fill()) return -1
        }
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        src.close()
    }
}

object Engine {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun sanitize(s: String): String = s.replace(ILLEGAL, "_")

    fun safeName(n: String): String {
        val s = sanitize(n).trim().trimEnd('.')
        return if (s.isEmpty()) "file" else s
    }

    fun normTemplate(t: String): String {
        val s = t.trim().ifEmpty { DEFAULT_TEMPLATE }
        return if (s.contains("{n}")) s else "$s.{n}"
    }

    fun normSuffix(s: String): String {
        var x = sanitize(s.trim()).replace(" ", "")
        if (x.isNotEmpty() && !x.startsWith(".")) x = ".$x"
        return x
    }

    /** Tokens: {name} {base} {ext} {n} (zero-padded part number) {total} */
    fun partName(name: String, template: String, suffix: String, total: Int, i: Int): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else ""
        val width = maxOf(4, total.toString().length)
        val n = String.format(Locale.ROOT, "%0" + width + "d", i)
        var s = normTemplate(template)
            .replace("{name}", name)
            .replace("{base}", base)
            .replace("{ext}", ext)
            .replace("{total}", total.toString())
            .replace("{n}", n)
        s = sanitize(s)
        if (s.startsWith(".")) s = "_$s"
        return s + normSuffix(suffix)
    }

    fun resolvePartSize(stream: Long, mode: String, req: Long, count: Int): Long =
        if (mode == "count") maxOf(1L, (stream + count - 1) / maxOf(1, count)) else req

    fun partCount(stream: Long, partSize: Long): Int =
        if (partSize <= 0L) 0 else ((stream + partSize - 1) / partSize).toInt()

    private fun partFileOf(dir: File, info: Info, i: Int): File =
        File(dir, partName(info.name, info.template, info.suffix, info.parts, i))

    private fun partLen(stream: Long, partSize: Long, i: Int): Long =
        minOf(partSize, stream - (i - 1).toLong() * partSize)

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    // ---------- natural ordering (for folders without info) ----------
    fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    // ---------- info file (hidden text) ----------
    fun readInfo(dir: File): Info? {
        val f = File(dir, Cfg.INFO)
        if (!f.isFile) return null
        return try {
            val p = Properties()
            InputStreamReader(FileInputStream(f), Charsets.UTF_8).use { p.load(it) }
            val size = p.getProperty("size")?.toLongOrNull() ?: return null
            Info(
                name = p.getProperty("name") ?: return null,
                size = size,
                streamSize = p.getProperty("streamSize")?.toLongOrNull() ?: size,
                partSize = p.getProperty("partSize")?.toLongOrNull() ?: 0L,
                parts = p.getProperty("parts")?.toIntOrNull() ?: 0,
                source = p.getProperty("source") ?: "",
                status = p.getProperty("status") ?: "UNKNOWN",
                template = p.getProperty("template") ?: DEFAULT_TEMPLATE,
                suffix = p.getProperty("suffix") ?: DEFAULT_SUFFIX,
                mode = p.getProperty("mode") ?: "size",
                reqSize = p.getProperty("reqSize")?.toLongOrNull() ?: DEFAULT_PART_SIZE,
                reqCount = p.getProperty("reqCount")?.toIntOrNull() ?: 0,
                zip = p.getProperty("zip") == "1",
                enc = p.getProperty("enc") == "1",
                stageReady = p.getProperty("stageReady") == "1"
            )
        } catch (e: Exception) {
            null
        }
    }

    fun writeInfo(dir: File, i: Info) {
        val p = Properties()
        p.setProperty("name", i.name)
        p.setProperty("size", i.size.toString())
        p.setProperty("streamSize", i.streamSize.toString())
        p.setProperty("partSize", i.partSize.toString())
        p.setProperty("parts", i.parts.toString())
        p.setProperty("source", i.source)
        p.setProperty("status", i.status)
        p.setProperty("template", i.template)
        p.setProperty("suffix", i.suffix)
        p.setProperty("mode", i.mode)
        p.setProperty("reqSize", i.reqSize.toString())
        p.setProperty("reqCount", i.reqCount.toString())
        p.setProperty("zip", if (i.zip) "1" else "0")
        p.setProperty("enc", if (i.enc) "1" else "0")
        p.setProperty("stageReady", if (i.stageReady) "1" else "0")
        val tmp = File(dir, Cfg.INFO + ".tmp")
        OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8).use {
            p.store(it, "FileSlicer - keeps the original file name and settings. Do not delete.")
        }
        val f = File(dir, Cfg.INFO)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun markStatus(dir: File, status: String) {
        try {
            val i = readInfo(dir) ?: return
            writeInfo(dir, i.copy(status = status))
        } catch (e: Exception) {
        }
    }

    private fun deleteParts(dir: File, info: Info) {
        if (info.parts <= 0) return
        for (i in 1..info.parts) partFileOf(dir, info, i).delete()
    }

    private fun wipe(dir: File, info: Info) {
        deleteParts(dir, info)
        File(dir, Cfg.STAGE).delete()
    }

    fun completeParts(dir: File, info: Info): Int {
        if (info.parts <= 0 || info.partSize <= 0) return 0
        var n = 0
        for (i in 1..info.parts) {
            val f = partFileOf(dir, info, i)
            if (f.isFile && f.length() == partLen(info.streamSize, info.partSize, i)) n++
        }
        return n
    }

    fun scanDownloads(): List<Entry> {
        val l = Cfg.downloads.listFiles() ?: return emptyList()
        return l.filter { it.isDirectory }
            .mapNotNull { d ->
                val info = readInfo(d)
                if (info != null) Entry(d, info, completeParts(d, info)) else null
            }
            .sortedByDescending { it.dir.lastModified() }
    }

    // ---------- inspect a folder before merging ----------
    private fun bad(dir: File, info: Info?, msg: String) =
        Check(dir, info, emptyList(), 0L, info?.name ?: "", msg, false, false, false)

    private fun head(f: File, n: Int): ByteArray = try {
        FileInputStream(f).use { s ->
            val b = ByteArray(n)
            val r = s.read(b)
            if (r <= 0) ByteArray(0) else b.copyOf(r)
        }
    } catch (e: Exception) {
        ByteArray(0)
    }

    private fun guessName(first: String, suffix: String): String {
        var n = first
        val suf = normSuffix(suffix)
        if (suf.isNotEmpty() && n.endsWith(suf, ignoreCase = true)) n = n.substring(0, n.length - suf.length)
        val g = n.replace(Regex("(?i)[._\\- ]*(part)?[._\\- ]*\\d+\$"), "")
        return if (g.isBlank()) n else g
    }

    fun inspect(dir: File, suffixFilter: String): Check {
        if (!dir.isDirectory) return bad(dir, null, "Folder not found")
        val info = readInfo(dir)
        val files: List<File>
        if (info != null) {
            if (info.status != "DONE" || info.parts <= 0) {
                return bad(dir, info, "Splitting of this file was not finished — resume it first (Split tab)")
            }
            files = (1..info.parts).map { partFileOf(dir, info, it) }
            val miss = files.indexOfFirst { !it.isFile }
            if (miss >= 0) return bad(dir, info, "Part ${miss + 1} of ${info.parts} is missing: ${files[miss].name}")
            val wrong = files.indices.firstOrNull {
                files[it].length() != partLen(info.streamSize, info.partSize, it + 1)
            }
            if (wrong != null) return bad(dir, info, "Part ${wrong + 1} has the wrong size (incomplete copy?)")
        } else {
            val suf = normSuffix(suffixFilter)
            val l = dir.listFiles()
                ?.filter {
                    it.isFile && !it.name.startsWith(".") &&
                        (suf.isEmpty() || it.name.endsWith(suf, ignoreCase = true))
                }
                ?.sortedWith(Comparator<File> { a, b -> natural(a.name, b.name) })
                ?: emptyList()
            if (l.isEmpty()) {
                return bad(dir, null, "No part files found (suffix filter: ${if (suf.isEmpty()) "none" else suf})")
            }
            files = l
        }
        val bytes = files.sumOf { it.length() }
        val h = head(files[0], 256)
        val txt = String(h, Charsets.ISO_8859_1)
        val isEnc = txt.startsWith(Cfg.MAGIC)
        val enc = info?.enc ?: isEnc
        val zipHdr = Regex("zip=(\\d)").find(txt.lineSequence().drop(1).firstOrNull() ?: "")
            ?.groupValues?.get(1) == "1"
        val pk = h.size >= 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() &&
            h[2] == 3.toByte() && h[3] == 4.toByte()
        val locked = info != null || isEnc
        val zip = when {
            info != null -> info.zip
            isEnc -> zipHdr
            else -> pk
        }
        val guess = info?.name ?: guessName(files[0].name, suffixFilter)
        return Check(dir, info, files, bytes, guess, null, enc, zip, locked)
    }

    // ---------- split ----------
    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k > 0) {
                left -= k
            } else {
                if (s.read() < 0) throw IOException("Source ended early")
                left--
            }
        }
    }

    /** zip and/or encrypt the source into the hidden .stage file */
    private fun buildStage(
        ctx: Context,
        job: Job,
        stage: File,
        cancel: () -> Boolean,
        progress: (Long, Long) -> Unit,
        phase: (String) -> Unit
    ) {
        phase(
            if (job.zip && job.enc) "Zipping + encrypting…"
            else if (job.zip) "Zipping…"
            else "Encrypting…"
        )
        val input = ctx.contentResolver.openInputStream(Uri.parse(job.uri))
            ?: throw IOException("Cannot open the source file (permission lost?)")
        val raw = BufferedOutputStream(FileOutputStream(stage), 1 shl 20)
        var ok = false
        try {
            input.use { s ->
                var top: OutputStream = raw
                if (job.enc) {
                    val rnd = SecureRandom()
                    val salt = ByteArray(16)
                    val np = ByteArray(4)
                    rnd.nextBytes(salt)
                    rnd.nextBytes(np)
                    val key = Crypto.key(job.password, salt)
                    val header = Cfg.MAGIC + "\n#salt=" + b64(salt) + ";np=" + b64(np) +
                        ";zip=" + (if (job.zip) "1" else "0") + "\n"
                    raw.write(header.toByteArray(Charsets.US_ASCII))
                    val wrapped = Base64.getMimeEncoder(76, byteArrayOf(10)).wrap(raw)
                    top = GcmEncOut(wrapped, key, np)
                }
                if (job.zip) {
                    val z = ZipOutputStream(top)
                    z.setLevel(Deflater.BEST_SPEED)
                    z.putNextEntry(ZipEntry(job.name))
                    top = z
                }
                val buf = ByteArray(Cfg.BUF)
                var done = 0L
                while (true) {
                    if (cancel()) throw Cancelled()
                    val n = s.read(buf)
                    if (n < 0) break
                    top.write(buf, 0, n)
                    done += n
                    progress(done, job.size)
                }
                top.close()
            }
            ok = true
        } finally {
            if (!ok) {
                try {
                    raw.close()
                } catch (e: Exception) {
                }
                stage.delete()
            }
        }
    }

    fun split(
        ctx: Context,
        job: Job,
        cancel: () -> Boolean,
        progress: (Long, Long) -> Unit,
        phase: (String) -> Unit
    ): File {
        val name = job.name
        val size = job.size
        if (size <= 0L) throw IOException("The file is empty or its size is unknown")
        if (job.mode == "count" && job.count < 1) throw IOException("Number of parts must be at least 1")
        if (job.mode != "count" && job.partSize < 1024) throw IOException("Part size is too small")

        val safe = safeName(name)
        val dir = File(Cfg.downloads, safe)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create ${dir.path} — is file access allowed?")
        }
        val template = normTemplate(job.template)
        val suffix = normSuffix(job.suffix)
        val needStage = job.zip || job.enc
        val stage = File(dir, Cfg.STAGE)

        val old = readInfo(dir)
        val reuse = old != null && old.size == size && old.template == template && old.suffix == suffix &&
            old.mode == job.mode && old.reqSize == job.partSize && old.reqCount == job.count &&
            old.zip == job.zip && old.enc == job.enc
        if (old != null && !reuse) wipe(dir, old)

        var info: Info = if (old != null && reuse) {
            old.copy(source = job.uri, status = "SPLITTING")
        } else {
            Info(
                name = name, size = size, streamSize = 0L, partSize = 0L, parts = 0, source = job.uri,
                status = "SPLITTING", template = template, suffix = suffix, mode = job.mode,
                reqSize = job.partSize, reqCount = job.count, zip = job.zip, enc = job.enc, stageReady = false
            )
        }
        writeInfo(dir, info)

        try {
            var streamSize = size
            if (needStage) {
                val ready = info.stageReady && stage.isFile && info.streamSize > 0L &&
                    stage.length() == info.streamSize
                if (!ready) {
                    if (job.enc && job.password.isEmpty()) {
                        throw IOException("Password needed — enter it in the Split tab, then tap Resume again")
                    }
                    deleteParts(dir, info)
                    info = info.copy(stageReady = false, streamSize = 0L, partSize = 0L, parts = 0)
                    writeInfo(dir, info)
                    buildStage(ctx, job, stage, cancel, progress, phase)
                    info = info.copy(stageReady = true, streamSize = stage.length())
                    writeInfo(dir, info)
                }
                streamSize = info.streamSize
            }

            val partSize = resolvePartSize(streamSize, job.mode, job.partSize, job.count)
            val parts = partCount(streamSize, partSize)
            if (parts > 100000) throw IOException("That would create $parts files — use bigger parts")
            info = info.copy(streamSize = streamSize, partSize = partSize, parts = parts)
            writeInfo(dir, info)
            val inf = info

            phase("Splitting…")
            var start = 1
            while (start <= parts) {
                val f = partFileOf(dir, inf, start)
                if (f.isFile && f.length() == partLen(streamSize, partSize, start)) start++ else break
            }
            var done = if (start > parts) streamSize else (start - 1).toLong() * partSize
            progress(done, streamSize)

            if (start <= parts) {
                val input: InputStream = if (needStage) {
                    FileInputStream(stage)
                } else {
                    ctx.contentResolver.openInputStream(Uri.parse(job.uri))
                        ?: throw IOException("Cannot open the source file (permission lost?)")
                }
                input.use { s ->
                    skipFully(s, (start - 1).toLong() * partSize)
                    val buf = ByteArray(Cfg.BUF)
                    for (i in start..parts) {
                        if (cancel()) throw Cancelled()
                        val out = partFileOf(dir, inf, i)
                        var left = partLen(streamSize, partSize, i)
                        var ok = false
                        try {
                            FileOutputStream(out).use { o ->
                                while (left > 0) {
                                    if (cancel()) throw Cancelled()
                                    val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (n < 0) throw IOException("The source ended early — was it changed or moved?")
                                    o.write(buf, 0, n)
                                    left -= n
                                    done += n
                                    progress(done, streamSize)
                                }
                            }
                            ok = true
                        } finally {
                            if (!ok) out.delete()
                        }
                    }
                }
            }
            if (needStage) stage.delete()
            info = info.copy(status = "DONE", stageReady = false)
            writeInfo(dir, info)
            return dir
        } catch (c: Cancelled) {
            markStatus(dir, "STOPPED")
            throw c
        } catch (t: Throwable) {
            markStatus(dir, "ERROR")
            throw t
        }
    }

    // ---------- merge ----------
    private fun withSuffix(n: String, k: Int): String {
        val d = n.lastIndexOf('.')
        return if (d > 0) n.substring(0, d) + " ($k)" + n.substring(d) else "$n ($k)"
    }

    private fun readLine(s: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = s.read()
            if (b < 0 || b == 10) break
            sb.append(b.toChar())
            if (sb.length > 500) throw IOException("Bad header")
        }
        return sb.toString()
    }

    /** decrypt (text -> bytes) and/or unzip the merged stream into the final file */
    private fun postProcess(
        src: File,
        dst: File,
        enc: Boolean,
        zip: Boolean,
        pw: String,
        total: Long,
        cancel: () -> Boolean,
        progress: (Long, Long) -> Unit,
        phase: (String) -> Unit
    ) {
        phase(if (enc && zip) "Decrypting + unzipping…" else if (enc) "Decrypting…" else "Unzipping…")
        var cnt = 0L
        val counting = object : FilterInputStream(BufferedInputStream(FileInputStream(src), 1 shl 20)) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) cnt++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    cnt += n
                    progress(cnt, total)
                }
                return n
            }
        }
        var stream: InputStream = counting
        try {
            if (enc) {
                val l1 = readLine(stream)
                if (l1 != Cfg.MAGIC) throw IOException("These parts are not FileSlicer encrypted text")
                val l2 = readLine(stream)
                val kv = l2.removePrefix("#").split(";").associate {
                    val i = it.indexOf('=')
                    if (i < 0) Pair(it, "") else Pair(it.substring(0, i), it.substring(i + 1))
                }
                val salt = Base64.getDecoder().decode(kv["salt"] ?: throw IOException("Bad header"))
                val np = Base64.getDecoder().decode(kv["np"] ?: throw IOException("Bad header"))
                val key = Crypto.key(pw, salt)
                stream = GcmDecIn(Base64.getMimeDecoder().wrap(stream), key, np)
            }
            if (zip) {
                val zis = ZipInputStream(stream)
                var e = zis.nextEntry
                while (e != null && e.isDirectory) e = zis.nextEntry
                if (e == null) throw IOException("The ZIP has no file inside")
                stream = zis
            }
            val buf = ByteArray(Cfg.BUF)
            FileOutputStream(dst).use { o ->
                while (true) {
                    if (cancel()) throw Cancelled()
                    val n = stream.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                }
            }
        } finally {
            try {
                stream.close()
            } catch (e: Exception) {
            }
            try {
                counting.close()
            } catch (e: Exception) {
            }
        }
    }

    fun merge(
        job: Job,
        cancel: () -> Boolean,
        progress: (Long, Long) -> Unit,
        phase: (String) -> Unit
    ): File {
        val dir = File(job.folder)
        val chk = inspect(dir, job.suffix)
        val problem = chk.problem
        if (problem != null) throw IOException(problem)
        val enc = chk.enc
        val zip = if (chk.zipLocked) chk.zip else job.zip
        if (enc && job.password.isEmpty()) throw IOException("These parts are encrypted — enter the password")

        val parts = chk.files
        val total = chk.bytes
        val outDir = Cfg.mergedDir
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("Cannot create ${outDir.path}")

        val clean = safeName(job.outName)
        val tag = Integer.toHexString(dir.absolutePath.hashCode())
        val tmp = File(outDir, "$clean.$tag.merging")
        if (tmp.exists() && tmp.length() > total) tmp.delete()

        // resume: continue from the last fully merged part boundary
        phase("Merging parts…")
        val have = if (tmp.exists()) tmp.length() else 0L
        var boundary = 0L
        var startIdx = 0
        while (startIdx < parts.size && boundary + parts[startIdx].length() <= have) {
            boundary += parts[startIdx].length()
            startIdx++
        }
        var done = boundary
        progress(done, total)

        val buf = ByteArray(Cfg.BUF)
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(boundary)
            raf.seek(boundary)
            for (i in startIdx until parts.size) {
                if (cancel()) throw Cancelled()
                FileInputStream(parts[i]).use { ins ->
                    while (true) {
                        if (cancel()) throw Cancelled()
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        }
        if (tmp.length() != total) throw IOException("Merged size mismatch — try again")

        var source = tmp
        if (enc || zip) {
            val out = File(outDir, "$clean.$tag.out")
            try {
                postProcess(tmp, out, enc, zip, job.password, total, cancel, progress, phase)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
            tmp.delete()
            source = out
        }

        var fin = File(outDir, clean)
        var k = 1
        while (fin.exists()) {
            fin = File(outDir, withSuffix(clean, k))
            k++
        }
        if (!source.renameTo(fin)) throw IOException("Could not finalize the merged file")
        return fin
    }
}
