package mx.org.fraac.geocampo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

class ImagerySettings(context: Context) {
    private val prefs = context.getSharedPreferences("geocampo_imagery", Context.MODE_PRIVATE)

    fun mapTilerKey(): String = prefs.getString(KEY_MAPTILER_API_KEY, "").orEmpty()

    fun setMapTilerKey(value: String) {
        prefs.edit().putString(KEY_MAPTILER_API_KEY, value.trim()).apply()
    }

    fun mapTilerPrintAllowed(): Boolean =
        prefs.getBoolean(KEY_MAPTILER_PRINT_ALLOWED, false)

    fun setMapTilerPrintAllowed(value: Boolean) {
        prefs.edit().putBoolean(KEY_MAPTILER_PRINT_ALLOWED, value).apply()
    }

    suspend fun validateMapTilerKey(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(key.isNotBlank()) { "Ingresa una API key de MapTiler." }

            val encoded = URLEncoder.encode(
                key.trim(),
                StandardCharsets.UTF_8.toString()
            )
            val url =
                "https://api.maptiler.com/maps/hybrid/256/tiles.json?key=$encoded"

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GeoCampo-Android/0.5.1")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val code = connection.responseCode
                when {
                    code in 200..299 -> Unit
                    code == 401 || code == 403 ->
                        error("La API key no fue aceptada por MapTiler.")
                    code == 429 ->
                        error("MapTiler alcanzó el límite de uso de la cuenta.")
                    else ->
                        error("MapTiler respondió HTTP $code.")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val KEY_MAPTILER_API_KEY = "maptiler_api_key"
        private const val KEY_MAPTILER_PRINT_ALLOWED = "maptiler_print_allowed"
    }
}
