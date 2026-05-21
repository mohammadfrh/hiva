package org.linphone.incomingcall

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.incomingcall.databinding.ActivityLoginBinding
import org.linphone.incomingcall.hiva.CaptchaImageLoader
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.LoginRequest
import org.linphone.incomingcall.hiva.parseApiDetailMessage
import retrofit2.HttpException

class LoginActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "HIVA_CAPTCHA"
    }

    private lateinit var binding: ActivityLoginBinding
    private var captchaKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IncomingCallApp
        if (app.sessionStore.isLoggedIn()) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonLogin.setOnClickListener { attemptLogin() }
        binding.imageCaptcha.setOnClickListener { loadCaptcha() }
        binding.buttonRefreshCaptcha.setOnClickListener { loadCaptcha() }

        loadCaptcha()
    }

    private fun loadCaptcha() {
        binding.progressCaptcha.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Log.i(TAG, "captcha-image API request")
                val res = HivaGoldClient.api.getCaptchaImage()
                captchaKey = res.captchaKey
                Log.i(TAG, "captcha-image API ok key=${captchaKey?.take(8)}... imageUrl=${res.imageUrl}")
                val bmp = withContext(Dispatchers.IO) {
                    CaptchaImageLoader.loadBitmapForPath(HivaGoldClient.okHttpClient, res.imageUrl)
                }
                binding.progressCaptcha.visibility = View.GONE
                if (bmp != null) {
                    Log.i(TAG, "UI captcha bitmap set ${bmp.width}x${bmp.height}")
                    binding.imageCaptcha.setImageBitmap(bmp)
                } else {
                    Log.w(TAG, "UI captcha bitmap null — check HIVA_CAPTCHA logs above")
                    Toast.makeText(
                        this@LoginActivity,
                        R.string.captcha_load_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                binding.progressCaptcha.visibility = View.GONE
                Toast.makeText(
                    this@LoginActivity,
                    e.message ?: getString(R.string.network_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun attemptLogin() {
        val user = binding.editUsername.text?.toString()?.trim().orEmpty()
        val pass = binding.editPassword.text?.toString().orEmpty()
        val captcha = binding.editCaptcha.text?.toString()?.trim().orEmpty()
        val key = captchaKey

        if (user.isEmpty() || pass.isEmpty() || captcha.isEmpty()) {
            Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }
        if (key.isNullOrEmpty()) {
            Toast.makeText(this, R.string.captcha_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressLogin.visibility = View.VISIBLE
        binding.buttonLogin.isEnabled = false

        lifecycleScope.launch {
            try {
                // 1. Verify Captcha
                val verifyReq = org.linphone.incomingcall.hiva.CaptchaVerifyRequest(key, captcha)
                HivaGoldClient.api.verifyCaptcha(verifyReq)

                // 2. Login
                val loginBody = org.linphone.incomingcall.hiva.LoginRequest(user, pass, key, captcha)
                
                // For Hiva Gold, login might return cookies. We need to handle them.
                // Since we don't have a CookieJar yet, we'll try to extract them if needed, 
                // but let's see if the login call itself works and we can get tokens.
                // The provided curl doesn't show response body, so we'll assume it might be empty or 
                // set sessionid in cookies.
                
                val loginRes = HivaGoldClient.api.login(loginBody)
                
                val app = application as IncomingCallApp
                if (!loginRes.access.isNullOrBlank()) {
                    app.sessionStore.accessToken = loginRes.access
                }
                if (!loginRes.refresh.isNullOrBlank()) {
                    app.sessionStore.refreshToken = loginRes.refresh
                }

                startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                finish()
            } catch (e: HttpException) {
                val msg = parseApiDetailMessage(e.response()?.errorBody()?.string())
                    .ifBlank { e.message() }
                Toast.makeText(
                    this@LoginActivity,
                    msg.ifBlank { getString(R.string.login_failed) },
                    Toast.LENGTH_LONG
                ).show()
                loadCaptcha()
                binding.editCaptcha.text?.clear()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    e.message ?: getString(R.string.network_error),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressLogin.visibility = View.GONE
                binding.buttonLogin.isEnabled = true
            }
        }
    }
}
