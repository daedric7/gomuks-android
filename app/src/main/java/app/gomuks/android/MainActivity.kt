package app.gomuks.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.net.CookieManager
import java.net.URI
import java.util.UUID

import android.graphics.Rect
import android.view.inputmethod.InputMethodManager
import android.view.animation.Interpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.core.view.animation.PathInterpolatorCompat

class MainActivity : ComponentActivity() {
    companion object {
        private const val LOGTAG = "Gomuks/MainActivity"
        private const val BUNDLE_KEY = "gecko"
        private var runtime: GeckoRuntime? = null

        private fun getRuntime(activity: MainActivity): GeckoRuntime {
            return runtime ?: run {
                val rt = GeckoRuntime.create(activity)
                rt.settings.enterpriseRootsEnabled = true
                rt.settings.consoleOutputEnabled = true
                rt.settings.doubleTapZoomingEnabled = false
                runtime = rt
                rt
            }
        }
    }
    private val navigation = NavigationDelegate(this)
    private val messageDelegate = MessageDelegate(this)
    internal val portDelegate = PortDelegate(this)
    private val promptDelegate = GeckoPrompts(this)

    private lateinit var view: GeckoView
    private lateinit var session: GeckoSession
    private var sessionState: GeckoSession.SessionState? = null

    internal lateinit var sharedPref: SharedPreferences
    private lateinit var prefEnc: Encryption
    internal lateinit var deviceID: UUID

    internal var port: WebExtension.Port? = null

    private fun initSharedPref() {
        sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        prefEnc = Encryption(getString(R.string.pref_enc_key_name))
        sharedPref.getString(getString(R.string.device_id_key), null).let {
            if (it == null) {
                deviceID = UUID.randomUUID()
                with(sharedPref.edit()) {
                    putString(getString(R.string.device_id_key), deviceID.toString())
                    apply()
                }
                Log.d(LOGTAG, "Generated new device ID $deviceID")
            } else {
                Log.d(LOGTAG, "Parsing UUID $it")
                deviceID = UUID.fromString(it)
            }
        }
    }

    internal fun getPushEncryptionKey(): String {
        return Base64.encodeToString(getOrCreatePushEncryptionKey(this, prefEnc, sharedPref), Base64.NO_WRAP)
    }

    private fun setCredentials(serverURL: String, username: String, password: String) {
        with(sharedPref.edit()) {
            putString(getString(R.string.server_url_key), serverURL)
            putString(getString(R.string.username_key), username)
            putString(getString(R.string.password_key), prefEnc.encrypt(password))
            apply()
        }
    }

    internal fun getCredentials(): Triple<String, String, String>? {
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), null)
        val username = sharedPref.getString(getString(R.string.username_key), null)
        val encPassword = sharedPref.getString(getString(R.string.password_key), null)
        if (serverURL == null || username == null || encPassword == null) {
            return null
        }
        try {
            return Triple(serverURL, username, prefEnc.decrypt(encPassword))
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to decrypt password", e)
            return null
        }
    }

    private fun storeGomuksAuthCookie(cookie: String) {
        with(sharedPref.edit()) {
            putString("gomuks_auth_cookie", cookie)
            apply()
        }
        logSharedPreferences()
    }

    private fun retrieveGomuksAuthCookie() {
        val serverUrl = sharedPref.getString(getString(R.string.server_url_key), null)
        if (serverUrl != null) {
            val cookieManager = GeckoRuntime.getDefaultCookieManager()
            val cookies = cookieManager.get(URI.create(serverUrl))
            val gomuksAuthCookie = cookies.find { it.name == "gomuks_auth" }?.value
            if (gomuksAuthCookie != null) {
                storeGomuksAuthCookie(gomuksAuthCookie)
            }
        } else {
            Log.e(LOGTAG, "Server URL is not set in shared preferences.")
        }
    }

    private fun logSharedPreferences() {
        val allEntries = sharedPref.all
        for ((key, value) in allEntries) {
            Log.d(LOGTAG, "SharedPreferences: $key = $value")
        }
    }

    private fun isSessionActive(sessionState: GeckoSession.SessionState?): Boolean {
        val stateJson = sessionState?.toString() ?: return false
        val jsonObject = JSONObject(stateJson)
        
        // Check if the session is active based on the JSON data
        val history = jsonObject.optJSONObject("history")
        val entries = history?.optJSONArray("entries")
        return entries != null && entries.length() > 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Try to request notifications permission 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                    1
                )
            }
        }

        //enable Edge to Edge rendering
        enableEdgeToEdge()

        // Hide bars
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Set the background color of the entire view to black, to prevent white space
        window.decorView.setBackgroundColor(resources.getColor(android.R.color.black))

        
        initSharedPref()
        createNotificationChannels(this)
        view = GeckoView(this)
        session = GeckoSession()
        val runtime = getRuntime(this)
        session.open(runtime)
        view.setSession(session)

        
        // Adjust view when keyboard opens. 
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            val keyboardHeight = imeInsets.bottom
            
            if (keyboardHeight > 0) {
                // Adjust bottom padding to make room for keyboard
                v.setPadding(
                    v.paddingLeft, 
                    v.paddingTop, 
                    v.paddingRight, 
                    keyboardHeight
                )
            } else {
                // Reset padding when keyboard is dismissed
                v.setPadding(
                    v.paddingLeft, 
                    v.paddingTop, 
                    v.paddingRight, 
                    0
                )
            }
            
            // Smooth out the padding change
            v.animate()
                .setDuration(250)
                .start()
            
            // Don't consume the insets
            insets
        }

        File(cacheDir, "upload").mkdirs()

        session.progressDelegate = object : ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                newState: GeckoSession.SessionState
            ) {
                super.onSessionStateChange(session, newState)
                Log.d(LOGTAG, "onSessionStateChange $newState")
                sessionState = newState
                if (isSessionActive(newState)) {
                    // Retrieve gomuks_auth cookie after session is active
                    retrieveGomuksAuthCookie()
                }
            }
        }
        session.promptDelegate = promptDelegate
        session.navigationDelegate = navigation

        val sessWebExtController = session.webExtensionController
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/bridge/", "android@gomuks.app")
            .accept(
                { extension ->
                    if (extension != null) {
                        Log.i(LOGTAG, "Extension installed: $extension")
                        sessWebExtController.setMessageDelegate(
                            extension,
                            messageDelegate,
                            "gomuksAndroid"
                        )
                    } else {
                        Log.e(LOGTAG, "Installed extension is null?")
                    }
                },
                { e -> Log.e(LOGTAG, "Error registering WebExtension", e) }
            )

        CoroutineScope(Dispatchers.Main).launch {
            tokenFlow.collect { pushToken ->
                Log.i(
                    LOGTAG,
                    "Received push token from messaging service: $pushToken"
                )
                portDelegate.registerPush(port ?: return@collect, pushToken)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigation.canGoBack) {
                    session.goBack()
                    return
                }
                finish()
            }
        })
        val parcel = savedInstanceState?.getParcelable(BUNDLE_KEY, GeckoSession.SessionState::class.java)
        if (parcel != null) {
            session.restoreState(parcel)
            setContentView(view)
        } else if (!loadWeb()) {
            setContent {
                ServerInput()
            }
        }
        Log.i(LOGTAG, "Initialization complete (loaded saved state: ${parcel != null})")
    }

    override fun onStart() {
        super.onStart()
        Log.i(LOGTAG, "onStart")
        session.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        Log.i(LOGTAG, "onPause")
    }

    override fun onResume() {
        super.onResume()
        Log.i(LOGTAG, "onResume")
    }

    override fun onStop() {
        super.onStop()
        Log.i(LOGTAG, "onStop")
        session.setActive(false)
    }

    override fun onRestart() {
        super.onRestart()
        Log.i(LOGTAG, "onRestart")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOGTAG, "onDestroy")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        Log.d(LOGTAG, "onSaveInstanceState $sessionState")
        outState.putParcelable(BUNDLE_KEY, sessionState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (this::session.isInitialized) {
            parseIntentURL(intent)?.let {
                session.loadUri(it)
            }
        }
    }

    fun getServerURL(): String? {
        return sharedPref.getString(getString(R.string.server_url_key), null)
    }

    fun openServerInputWithError(error: String) {
        val (serverURL, username, password) = getCredentials() ?: Triple("", "", "")
        setContent {
            ServerInput(serverURL, username, password, error)
        }
    }

    private fun parseIntentURL(overrideIntent: Intent? = null): String? {
        var serverURL = getServerURL() ?: return null
        val intent = overrideIntent ?: this.intent
        var targetURI = intent.data
        if (intent.action == Intent.ACTION_VIEW && targetURI != null) {
            if (targetURI.host == "matrix.to") {
                targetURI = matrixToURLToMatrixURI(targetURI)
                if (targetURI == null) {
                    Log.w(LOGTAG, "Failed to parse matrix.to URL ${intent.data}")
                } else {
                    Log.d(LOGTAG, "Parsed matrix.to URL ${intent.data} -> $targetURI")
                }
            }
            if (targetURI?.scheme == "matrix") {
                serverURL = Uri.parse(serverURL)
                    .buildUpon()
                    .encodedFragment("/uri/${Uri.encode(targetURI.toString())}")
                    .build()
                    .toString()
                Log.d(LOGTAG, "Converted view intent $targetURI -> $serverURL")
                return serverURL
            }
        }
        if (overrideIntent != null) {
            Log.w(
                LOGTAG,
                "No intent URL found ${overrideIntent.action} ${overrideIntent.data}"
            )
            return null
        }
        return serverURL
    }

    private fun loadWeb(): Boolean {
        session.loadUri(parseIntentURL() ?: return false)
        setContentView(view)
        return true
    }

    @Composable
    fun ServerInput(
        initialURL: String = "",
        initialUsername: String = "",
        initialPassword: String = "",
        error: String? = null
    ) {
        var serverURL by remember { mutableStateOf(initialURL) }
        var username by remember { mutableStateOf(initialUsername) }
        var password by remember { mutableStateOf(initialPassword) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextField(
                value = serverURL,
                onValueChange = { serverURL = it },
                label = { Text(getString(R.string.server_url)) }
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(getString(R.string.username)) }
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(getString(R.string.password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Button(onClick = {
                setCredentials(serverURL, username, password)
                loadWeb()
            }) {
                Text(getString(R.string.connect))
            }
            if (error != null) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
