package com.github.kr328.clash
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cicy.agent.adr.ACT_REQUEST_MEDIA_PROJECTION
import com.cicy.agent.adr.LocalServer
import com.cicy.agent.adr.MessageActivityHandler
import com.cicy.agent.adr.MessageHandler
import com.cicy.agent.adr.PermissionRequestTransparentActivity
import com.cicy.agent.adr.REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION
import com.cicy.agent.adr.RES_FAILED
import com.cicy.agent.adr.RecordingService
import com.github.kr328.clash.design.Design

abstract class BaseMainActivity<D : Design<*>> : BaseActivity<D>() {
    var recordingService: RecordingService? = null

    lateinit var localBroadcastManager: LocalBroadcastManager
    lateinit var messageHandler: MessageActivityHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, LocalServer::class.java))
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(
            serviceRequestReceiver,
            IntentFilter(MessageHandler.ACTION_REQUEST)
        )
        messageHandler = MessageActivityHandler(this)
    }
    override fun onDestroy() {
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
            updateRecordingStatus("on_media_projection_canceled")
        }else{
            updateRecordingStatus("state_change")
        }
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
                bindService(it, serviceConnection, BIND_AUTO_CREATE)
            }
            requestMediaProjection()
        }
    }

    fun stopRecording(){
        recordingService?.destroy()
    }


    fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION  // Correct way to set action
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

}
