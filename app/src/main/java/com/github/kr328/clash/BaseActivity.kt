package com.github.kr328.clash
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cicy.agent.adr.ACT_REQUEST_MEDIA_PROJECTION
import com.cicy.agent.adr.InputService
import com.cicy.agent.adr.LocalServer
import com.cicy.agent.adr.MessageActivityHandler
import com.cicy.agent.adr.MessageHandler
import com.cicy.agent.adr.PermissionRequestTransparentActivity
import com.cicy.agent.adr.REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION
import com.cicy.agent.adr.RES_FAILED
import com.cicy.agent.adr.RecordingService
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isLightNavigationBarCompat
import com.github.kr328.clash.common.compat.isLightStatusBarsCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.DayNight
import com.github.kr328.clash.design.util.resolveThemedBoolean
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.ActivityResultLifecycle
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

abstract class BaseActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope by MainScope(),
    Broadcasts.Observer {


    var recordingService: RecordingService? = null

    lateinit var localBroadcastManager: LocalBroadcastManager
    lateinit var messageHandler: MessageActivityHandler

    protected val uiStore by lazy { UiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)
    protected var activityStarted: Boolean = false
    protected val clashRunning: Boolean
        get() = Remote.broadcasts.clashRunning

    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) {
                setContentView(value.root)
            } else {
                setContentView(View(this))
            }
        }

    private var defer: suspend () -> Unit = {}
    private var deferRunning = false
    private val nextRequestKey = AtomicInteger(0)
    private var dayNight: DayNight = DayNight.Day

    protected abstract suspend fun main()

    fun defer(operation: suspend () -> Unit) {
        this.defer = operation
    }

    suspend fun <I, O> startActivityForResult(
        contracts: ActivityResultContract<I, O>,
        input: I,
    ): O = withContext(Dispatchers.Main) {
        val requestKey = nextRequestKey.getAndIncrement().toString()

        ActivityResultLifecycle().use { lifecycle, start ->
            suspendCoroutine { c ->
                activityResultRegistry.register(requestKey, lifecycle, contracts) {
                    c.resume(it)
                }.apply { start() }.launch(input)
            }
        }
    }

    suspend fun setContentDesign(design: D) {
        suspendCoroutine<Unit> {
            window.decorView.post {
                this.design = design
                it.resume(Unit)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(this, Intent(this, LocalServer::class.java))
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(
            serviceRequestReceiver,
            IntentFilter(MessageHandler.ACTION_REQUEST)
        )
        messageHandler = MessageActivityHandler(this)

        applyDayNight()
        launch {
            main()
        }
    }

    override fun onResume() {
        super.onResume()

    }
    override fun onStart() {
        super.onStart()
        activityStarted = true
        Remote.broadcasts.addObserver(this)
        events.trySend(Event.ActivityStart)
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        Remote.broadcasts.removeObserver(this)
        events.trySend(Event.ActivityStop)
    }


    override fun finish() {
        if (deferRunning) return
        deferRunning = true

        launch {
            try {
                defer()
            } finally {
                withContext(NonCancellable) {
                    super.finish()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (queryDayNight(newConfig) != dayNight) {
            ApplicationObserver.createdActivities.forEach {
                it.recreate()
            }
        }
    }

    open fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        this.onBackPressed()
        return true
    }

    override fun onProfileChanged() {
        events.trySend(Event.ProfileChanged)
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        events.trySend(Event.ProfileUpdateCompleted)
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        events.trySend(Event.ProfileUpdateFailed)
    }

    override fun onProfileLoaded() {
        events.trySend(Event.ProfileLoaded)
    }

    override fun onServiceRecreated() {
        events.trySend(Event.ServiceRecreated)
    }

    override fun onStarted() {
        events.trySend(Event.ClashStart)
    }

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showExceptionToast(ClashException(cause))
            }
        }
    }

    private fun queryDayNight(config: Configuration = resources.configuration): DayNight {
        return when (uiStore.darkMode) {
            DarkMode.Auto -> if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DayNight.Night else DayNight.Day
            DarkMode.ForceLight -> DayNight.Day
            DarkMode.ForceDark -> DayNight.Night
        }
    }

    private fun applyDayNight(config: Configuration = resources.configuration) {
        val dayNight = queryDayNight(config)
        when (dayNight) {
            DayNight.Night -> theme.applyStyle(R.style.AppThemeDark, true)
            DayNight.Day -> theme.applyStyle(R.style.AppThemeLight, true)
        }

        window.isAllowForceDarkCompat = false
        window.isSystemBarsTranslucentCompat = true
        
        window.statusBarColor = resolveThemedColor(android.R.attr.statusBarColor)
        window.navigationBarColor = resolveThemedColor(android.R.attr.navigationBarColor)

        if (Build.VERSION.SDK_INT >= 23) {
            window.isLightStatusBarsCompat = resolveThemedBoolean(android.R.attr.windowLightStatusBar)
        }

        if (Build.VERSION.SDK_INT >= 27) {
            window.isLightNavigationBarCompat = resolveThemedBoolean(android.R.attr.windowLightNavigationBar)
        }

        this.dayNight = dayNight
    }

    override fun onDestroy() {
        design?.cancel()
        cancel()

        recordingService?.let {
            unbindService(serviceConnection)
        }

        localBroadcastManager.unregisterReceiver(serviceRequestReceiver)
        super.onDestroy()
    }


    @RequiresApi(Build.VERSION_CODES.O)
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
//            sendMessageToWebView(JSONObject().apply {
//                put("action", "on_media_projection_canceled")
//            }.toString())
        }
//        onStateChanged()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, serviceBinder: IBinder?) {
            val binder = serviceBinder as RecordingService.LocalBinder
            recordingService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
        }
    }
    fun startRecording(){
        if (!RecordingService.isReady) {
            Intent(this, RecordingService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            requestMediaProjection()
        }
    }

    fun stopRecording(){
        recordingService?.destroy()
    }

    fun isClashRunning(): Boolean {
        return clashRunning
    }

    fun startClash(): String {
        if(!isClashRunning()){
            val vpnRequest = startClashService()
            if (vpnRequest != null) {
                Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
            }else{
                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
            }
        }
        return "clash trigger start"
    }

    fun stopClash():String {
        if(isClashRunning()){
            stopClashService()
            Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
        }
        return "clash trigger stop"
    }

    private suspend fun createUUID(name: String, url: String): UUID {
        val uuid = withProfile {
            create(Profile.Type.Url, name).also {
                patch(it, name, url, 0)
            }
        }
        withProfile {
            commit(uuid)
            queryByUUID(uuid)?.let { profile ->
                setActive(profile)
            } ?: throw IllegalStateException("Profile not found after creation")
        }
        getSharedPreferences("CLASH_CONFIG", MODE_PRIVATE).edit {
            putString("currentUUID", uuid.toString())
            apply()
        }
        return uuid
    }

    fun updateClash(){
        if(isClashRunning()){
            stopClashService()
        }
        val url = "http://127.0.0.1:${LocalServer.PORT}/clashConfig.yaml"
        val name = "代理 ${SimpleDateFormat("dd HH:mm", Locale.getDefault()).format(Date())}"
        launch {
            val prefs = getSharedPreferences("CLASH_CONFIG", MODE_PRIVATE)
            val currentUUID = prefs.getString("currentUUID", null)
            if(currentUUID == null){
                createUUID(name,url)
                startClashDelay()
            }else{
                val uuid = UUID.fromString(currentUUID)
                withProfile {
                    val profile = queryByUUID(uuid)
                    if(profile == null){
                        createUUID(name,url)
                        startClashDelay()
                    }else{
                        patch(uuid, name, url, 0)
                        update(uuid)
                        setActive(profile)
                        startClashDelay()
                    }
                }
            }
        }

    }
    private fun startClashDelay(){
        launch(Dispatchers.Main) {
            delay(100L)
            if(!isClashRunning()){
                while (true){
                    delay(200L)
                    if(!isClashRunning()){
                        startClash()
                        break
                    }
                }
            }else{
                startClash()
            }
        }
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent,
            REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION
        )
    }
    @CallSuper
    open fun updateRecordingStatus(state:String){}
    private val serviceRequestReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MessageHandler.ACTION_REQUEST) return
            val messageAsync = intent.getStringExtra(MessageHandler.EXTRA_MESSAGE_ASYNC)
            if(messageAsync !== null){
                messageHandler.processAsync(messageAsync)
                when(messageAsync){
                    "on_screen_recording" -> updateRecordingStatus(messageAsync)
                    "on_recording_state_changed" -> updateRecordingStatus(messageAsync)
                    "on_screen_stopped_recording" -> updateRecordingStatus(messageAsync)
                }
            }
            val message = intent.getStringExtra(MessageHandler.EXTRA_MESSAGE)
            if(message !== null){
                val callbackId = intent.getStringExtra(MessageHandler.EXTRA_CALLBACK_ID)
                messageHandler.process(message,callbackId)
            }
        }
    }


    enum class Event {
        ServiceRecreated,
        ActivityStart,
        ActivityStop,
        ClashStop,
        ClashStart,
        ProfileLoaded,
        ProfileChanged,
        ProfileUpdateCompleted,
        ProfileUpdateFailed,
    }
}
