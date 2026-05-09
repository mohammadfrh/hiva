package org.linphone.incomingcall.bot.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.linphone.incomingcall.IncomingCallApp
import org.linphone.incomingcall.R
import org.linphone.incomingcall.bot.prettyJson
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.LoginRequest
import org.linphone.incomingcall.hiva.LoginResponse
import org.linphone.incomingcall.hiva.CaptchaImageResponse

class BotSettingsFragment : Fragment(R.layout.fragment_bot_settings) {
    private var captchaKey: String = ""
    private val profiles = listOf("baseline", "long_protection", "scaled_units")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as IncomingCallApp
        val out = view.findViewById<TextView>(R.id.textSettingsOut)
        val backend = view.findViewById<EditText>(R.id.editBackendBase)
        val status = view.findViewById<TextView>(R.id.textSettingsStatus)
        val profileSpinner = view.findViewById<Spinner>(R.id.spinnerStrategyProfile)
        val autoTradeSwitch = view.findViewById<Switch>(R.id.switchAutoTrade)
        val strategyJson = view.findViewById<EditText>(R.id.editStrategyJson)
        val hivaBase = view.findViewById<EditText>(R.id.editHivaBaseUrl)
        val hivaWs = view.findViewById<EditText>(R.id.editHivaWsUrl)
        val hivaSymbol = view.findViewById<EditText>(R.id.editHivaSymbol)
        val accessToken = view.findViewById<EditText>(R.id.editAccessToken)
        val refreshToken = view.findViewById<EditText>(R.id.editRefreshToken)
        val csrfToken = view.findViewById<EditText>(R.id.editCsrfToken)
        val user = view.findViewById<EditText>(R.id.editBotUser)
        val pass = view.findViewById<EditText>(R.id.editBotPass)
        val captchaVal = view.findViewById<EditText>(R.id.editBotCaptchaValue)
        val image = view.findViewById<ImageView>(R.id.imageBotCaptcha)

        backend.setText(app.botPrefs.backendBaseUrl)
        status.text = getString(R.string.bot_settings_status_empty)
        val profileAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, profiles)
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = profileAdapter
        profileSpinner.setSelection(profiles.indexOf(app.botPrefs.localProfileId).coerceAtLeast(0))

        view.findViewById<Button>(R.id.buttonSaveBackendBase).setOnClickListener {
            app.botPrefs.backendBaseUrl = backend.text.toString().trim()
            out.text = getString(R.string.saved)
        }
        view.findViewById<Button>(R.id.buttonConfigGet).setOnClickListener {
            val res = localConfigJson(app)
            bindSettingsStatus(status, res)
            out.text = res.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonRefreshToken).setOnClickListener {
            val res = JsonObject().apply {
                addProperty("ok", false)
                addProperty("message", "Local mode: refresh token endpoint disabled")
            }
            bindSettingsStatus(status, res)
            out.text = res.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonAuthStatus).setOnClickListener {
            val res = JsonObject().apply {
                addProperty("locked", false)
                addProperty("reason", "")
            }
            bindSettingsStatus(status, res)
            out.text = res.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonAuthUnlock).setOnClickListener {
            val res = JsonObject().apply { addProperty("ok", true) }
            bindSettingsStatus(status, res)
            out.text = res.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonSaveStrategyConfig).setOnClickListener {
            app.botPrefs.localProfileId = profileSpinner.selectedItem?.toString().orEmpty().ifBlank { "baseline" }
            val body = JsonObject().apply {
                addProperty("profile_id", profileSpinner.selectedItem?.toString().orEmpty().ifBlank { "baseline" })
                addProperty("auto_trade", autoTradeSwitch.isChecked)
            }
            val extra = strategyJson.text.toString().trim()
            if (extra.isNotBlank()) {
                val extraObj = JsonParser.parseString(extra).asJsonObject
                for (key in extraObj.keySet()) body.add(key, extraObj.get(key))
            }
            bindSettingsStatus(status, body)
            out.text = body.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonSaveConnectionConfig).setOnClickListener {
            app.botPrefs.backendBaseUrl = hivaBase.text.toString().trim().ifBlank { app.botPrefs.backendBaseUrl }
            val body = JsonObject().apply {
                addProperty("ok", true)
                addProperty("hiva_base_url", hivaBase.text.toString().trim())
                addProperty("hiva_ws_url", hivaWs.text.toString().trim())
                addProperty("hiva_symbol", hivaSymbol.text.toString().trim())
            }
            bindSettingsStatus(status, body)
            out.text = body.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonSaveTokens).setOnClickListener {
            app.sessionStore.accessToken = accessToken.text.toString().trim()
            app.sessionStore.refreshToken = refreshToken.text.toString().trim()
            app.sessionStore.csrfToken = csrfToken.text.toString().trim()
            val body = JsonObject().apply { addProperty("ok", true); addProperty("message", "tokens saved locally") }
            bindSettingsStatus(status, body)
            out.text = body.prettyJson()
        }
        view.findViewById<Button>(R.id.buttonCaptcha).setOnClickListener {
            loadCaptchaLocal(out, status, image)
        }
        view.findViewById<Button>(R.id.buttonBotLogin).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                out.text = getString(R.string.loading)
                try {
                    val res = HivaGoldClient.api.login(
                        LoginRequest(
                            username = user.text.toString().trim(),
                            password = pass.text.toString(),
                            captchaKey = captchaKey,
                            captchaValue = captchaVal.text.toString().trim()
                        )
                    )
                    if (!res.access.isNullOrBlank()) {
                        app.sessionStore.accessToken = res.access
                    }
                    if (!res.refresh.isNullOrBlank()) {
                        app.sessionStore.refreshToken = res.refresh
                    }
                    val body = JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("username", res.user?.username ?: "session_user")
                        addProperty("referral_code", res.user?.referralCode ?: "-")
                        addProperty("message", res.message ?: "login success (local mode)")
                    }
                    bindSettingsStatus(status, body)
                    out.text = body.prettyJson()
                } catch (e: Exception) {
                    out.text = e.message ?: getString(R.string.network_error)
                }
            }
        }
        loadCaptchaLocal(out, status, image)
        val cfg = localConfigJson(app)
        bindSettingsStatus(status, cfg)
        out.text = cfg.prettyJson()
        hivaBase.setText(HivaGoldClient.BASE_URL)
        hivaWs.setText("")
        hivaSymbol.setText("mazane")
        accessToken.setText(app.sessionStore.accessToken.orEmpty())
        refreshToken.setText(app.sessionStore.refreshToken.orEmpty())
        csrfToken.setText(app.sessionStore.csrfToken.orEmpty())
    }

    private fun loadCaptchaLocal(out: TextView, status: TextView, image: ImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            out.text = getString(R.string.loading)
            try {
                val json = HivaGoldClient.api.getCaptchaImage()
                captchaKey = json.captchaKey
                val imageUrl = json.imageUrl
                if (imageUrl.isNotBlank()) {
                    val url = HivaGoldClient.BASE_URL.trimEnd('/') + "/api" + imageUrl
                    val bmp = withContext(Dispatchers.IO) {
                        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                            if (!resp.isSuccessful) return@withContext null
                            resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                        }
                    }
                    if (bmp != null) image.setImageBitmap(bmp)
                }
                val res = JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("captcha_key", captchaKey)
                }
                bindSettingsStatus(status, res)
                out.text = res.prettyJson()
            } catch (e: Exception) {
                out.text = e.message ?: getString(R.string.network_error)
            }
        }
    }

    private fun localConfigJson(app: IncomingCallApp): JsonObject {
        return JsonObject().apply {
            addProperty("ws_connected", false)
            addProperty("profile_id", app.botPrefs.localProfileId)
            addProperty("hiva_symbol", "mazane")
            addProperty("auto_trade", false)
            addProperty("access_set", !app.sessionStore.accessToken.isNullOrBlank())
            addProperty("refresh_set", !app.sessionStore.refreshToken.isNullOrBlank())
        }
    }

    private fun bindSettingsStatus(status: TextView, res: JsonElement) {
        if (!res.isJsonObject) {
            status.text = getString(R.string.bot_settings_status_empty)
            return
        }
        val obj = res.asJsonObject
        status.text = when {
            obj.has("locked") -> {
                val locked = obj.get("locked")?.asString ?: "-"
                val reason = obj.get("reason")?.asString ?: "-"
                "auth_locked: $locked | reason: $reason"
            }
            obj.has("ws_connected") -> {
                val ws = obj.get("ws_connected")?.asString ?: "-"
                val profile = obj.get("profile_id")?.asString ?: "-"
                val symbol = obj.get("hiva_symbol")?.asString ?: "-"
                "ws: $ws | profile: $profile | symbol: $symbol"
            }
            obj.has("ok") && obj.has("message") -> {
                "ok: ${obj.get("ok").asString} | ${obj.get("message").asString}"
            }
            obj.has("ok") -> "ok: ${obj.get("ok").asString}"
            else -> "keys: ${obj.keySet().size}"
        }
    }
}
