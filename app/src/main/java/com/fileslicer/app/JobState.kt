package com.fileslicer.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Locale

const val DEFAULT_TEMPLATE = "{name}.part{n}"
const val DEFAULT_SUFFIX = ".pdf"
const val DEFAULT_PART_SIZE = 10L * 1024 * 1024

/**
 * kind = "split" or "merge".
 * split: name/size/uri + template/suffix/mode/partSize/count/zip/enc/password
 * merge: folder/outName + suffix (filter, only if folder has no info) + zip (only if not auto-detected) + password
 */
data class Job(
    val kind: String,
    val uri: String = "",
    val name: String = "",
    val size: Long = 0L,
    val folder: String = "",
    val outName: String = "",
    val template: String = DEFAULT_TEMPLATE,
    val suffix: String = DEFAULT_SUFFIX,
    val mode: String = "size",
    val partSize: Long = DEFAULT_PART_SIZE,
    val count: Int = 0,
    val zip: Boolean = false,
    val enc: Boolean = false,
    val password: String = ""
) {
    fun title(): String = if (kind == "split") "Splitting $name" else "Merging $outName"

    fun toJson(withPassword: Boolean): String {
        val o = JSONObject()
        o.put("kind", kind)
        o.put("uri", uri)
        o.put("name", name)
        o.put("size", size)
        o.put("folder", folder)
        o.put("outName", outName)
        o.put("template", template)
        o.put("suffix", suffix)
        o.put("mode", mode)
        o.put("partSize", partSize)
        o.put("count", count)
        o.put("zip", zip)
        o.put("enc", enc)
        if (withPassword) o.put("password", password)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): Job? = try {
            val o = JSONObject(s)
            Job(
                o.getString("kind"),
                o.optString("uri", ""),
                o.optString("name", ""),
                o.optLong("size", 0L),
                o.optString("folder", ""),
                o.optString("outName", ""),
                o.optString("template", DEFAULT_TEMPLATE),
                o.optString("suffix", DEFAULT_SUFFIX),
                o.optString("mode", "size"),
                o.optLong("partSize", DEFAULT_PART_SIZE),
                o.optInt("count", 0),
                o.optBoolean("zip", false),
                o.optBoolean("enc", false),
                o.optString("password", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class UiJob(
    val running: Boolean = false,
    val kind: String = "",
    val title: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
    val speed: Long = 0L,
    val result: String = "",
    val error: Boolean = false,
    val phase: String = ""
)

object JobState {
    val flow = MutableStateFlow(UiJob())
}

object Control {
    @Volatile
    var cancel = false
}

/** Persists the active job (WITHOUT the password) so the service can auto-resume if Android kills it. */
object Store {
    private fun sp(c: Context) = c.getSharedPreferences("active_job", Context.MODE_PRIVATE)

    fun save(c: Context, j: Job) {
        sp(c).edit().putString("job", j.toJson(false)).commit()
    }

    fun load(c: Context): Job? {
        val s = sp(c).getString("job", null) ?: return null
        return Job.fromJson(s)
    }

    fun clear(c: Context) {
        sp(c).edit().clear().commit()
    }

    fun put(i: Intent, j: Job): Intent {
        i.putExtra("job", j.toJson(true))
        return i
    }

    fun get(i: Intent): Job? {
        val s = i.getStringExtra("job") ?: return null
        return Job.fromJson(s)
    }
}

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < u.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.ROOT, "%.1f %s", v, u[i])
}
