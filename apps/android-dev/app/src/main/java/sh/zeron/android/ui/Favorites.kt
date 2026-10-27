package sh.zeron.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Starred models, as on the desktop's model picker: starred rows float to the
 * top of their agent's list, and a Favorites section mixes agents. Kept on
 * this phone. The label is stored so a favorite from another agent can be
 * listed without loading that agent's catalog.
 */
object Favorites {
    data class Fav(val harness: String, val model: String, val label: String)

    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("zeron", Context.MODE_PRIVATE)

    /** Bumps on every change; composables key on it. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun all(): List<Fav> {
        val raw = prefs.getString("favoriteModels", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Fav(o.getString("harness"), o.getString("model"), o.optString("label", o.getString("model")))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isFavorite(harness: String, model: String) = all().any { it.harness == harness && it.model == model }

    fun toggle(harness: String, model: String, label: String) {
        val current = all()
        val next = if (current.any { it.harness == harness && it.model == model }) {
            current.filterNot { it.harness == harness && it.model == model }
        } else {
            current + Fav(harness, model, label)
        }
        val a = JSONArray()
        for (f in next) a.put(JSONObject().put("harness", f.harness).put("model", f.model).put("label", f.label))
        prefs.edit().putString("favoriteModels", a.toString()).apply()
        version++
    }
}
