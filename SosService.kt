package com.pathra.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.drawable.Icon
import android.hardware.*
import android.location.Location
import android.os.*
import android.telephony.SmsManager
import com.google.android.gms.location.*
import org.json.JSONObject
import kotlin.math.sqrt

class SosService : Service(), SensorEventListener {
    companion object {
        const val ACT_SOS = "pathra.SOS"; const val ACT_STOP = "pathra.STOP"
        const val ACT_YES = "pathra.YES"; const val ACT_NO = "pathra.NO"
        const val POWER_PRESSES = 3          // screen on/off toggles within 3.5 s
    }
    private lateinit var nm: NotificationManager
    private lateinit var fused: FusedLocationProviderClient
    private var warming = false; private var refine = false
    private val warmStop = Handler(Looper.getMainLooper())
    private val shakes = ArrayList<Long>(); private val screenEv = ArrayList<Long>()
    private var cooldownUntil = 0L; private var lastShakeT = 0L
    private var live = false; private var seeded = false
    private var lastLoc: Location? = null; private var lastSent: Location? = null; private var lastSentAt = 0L

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> if (cfg().optBoolean("power", true)) {
                    val now = System.currentTimeMillis()
                    screenEv.add(now); screenEv.removeAll { now - it > 3500 }
                    if (screenEv.size >= POWER_PRESSES && now > cooldownUntil) { screenEv.clear(); askConfirm("Power button pressed ${POWER_PRESSES} times") }
                }
                ACT_YES -> { nm.cancel(2); sendSos() }
                ACT_NO -> nm.cancel(2)
                ACT_STOP -> stopSos()
            }
        }
    }

    override fun onCreate() {
        nm = getSystemService(NotificationManager::class.java)
        fused = LocationServices.getFusedLocationProviderClient(this)
        nm.createNotificationChannel(NotificationChannel("watch", "Pathra protection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alert", "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400) })
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACT_YES); addAction(ACT_NO); addAction(ACT_STOP) }
        registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        val sm = getSystemService(SensorManager::class.java)
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val n = Notification.Builder(this, "watch").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (live) "SOS active – sharing live location" else "Pathra protection on")
            .setContentText("Shake or press power 3× to trigger SOS").setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }   // e.g. location permission not granted yet
        seedLocation()
        when (i?.action) { ACT_SOS -> sendSos(); ACT_STOP -> stopSos() }
        return START_STICKY
    }

    // ---- shake: 3 strong shakes in 2 s ----
    override fun onSensorChanged(e: SensorEvent) {
        if (!cfg().optBoolean("shake", true)) return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
        val now = System.currentTimeMillis()
        if (g > 2.7f && now - lastShakeT > 250 && now > cooldownUntil) {
            lastShakeT = now; shakes.add(now); shakes.removeAll { now - it > 2000 }
            if (shakes.size >= 3) { shakes.clear(); askConfirm("Shake detected") }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun pi(a: String) = PendingIntent.getBroadcast(this, a.hashCode(),
        Intent(a).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun act(t: String, a: String) = Notification.Action.Builder(
        Icon.createWithResource(this, android.R.drawable.ic_menu_send), t, pi(a)).build()

    // ---- location: always keep a fresh fix so SOS never waits for GPS ----
    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { onFix(it) } }
    }
    @SuppressLint("MissingPermission")
    private fun seedLocation() = try {
        if (seeded || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw Exception()
        seeded = true
        fused.lastLocation.addOnSuccessListener { if (it != null) lastLoc = it }
        // passive = free: receives fixes other apps already requested
        fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 0).build(),
            object : LocationCallback() { override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { lastLoc = it } } },
            Looper.getMainLooper())
    } catch (_: Exception) {}

    @SuppressLint("MissingPermission")
    private fun warmUp() {      // start GPS the moment the confirm notification shows
        if (warming) return; warming = true
        try { fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500).build(), cb, Looper.getMainLooper()) } catch (_: Exception) {}
        warmStop.postDelayed({ if (!live) coolDown() }, 60000)
    }
    private fun coolDown() { warming = false; fused.removeLocationUpdates(cb) }

    private fun askConfirm(why: String) {
        cooldownUntil = System.currentTimeMillis() + 15000
        warmUp()
        nm.notify(2, Notification.Builder(this, "alert").setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$why – send SOS?").setContentText("Tap YES to alert your emergency contacts now.")
            .setCategory(Notification.CATEGORY_ALARM).setTimeoutAfter(60000)
            .addAction(act("YES – SEND SOS", ACT_YES)).addAction(act("No", ACT_NO)).build())
    }

    // ---- SOS: SMS goes out instantly with the best fix we have, then a precise one follows ----
    private fun sendSos() {
        live = true; lastSent = null; refine = true
        warmUp()
        val l = lastLoc
        val name = cfg().optString("name")
        if (l != null) {
            val age = (System.currentTimeMillis() - l.time) / 1000
            sendLoc("🆘 SOS! $name needs help." + if (age > 20) " Last known location (${age}s ago):" else " Live location:", l)
        } else sms("🆘 SOS! $name needs help. Location is being acquired – exact location follows in seconds.")
        nm.notify(1, Notification.Builder(this, "watch").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("SOS active – sharing live location").setOngoing(true)
            .addAction(act("I'm safe – stop", ACT_STOP)).build())
    }

    private fun onFix(l: Location) {
        lastLoc = l
        if (!live) return
        val now = System.currentTimeMillis()
        val ls = lastSent
        if (ls == null) { sendLoc("🆘 Location fixed:", l); return }
        if (refine && l.accuracy < 50f) { refine = false; sendLoc("📍 Precise location:", l); return }
        if (l.distanceTo(ls) > 40f && now - lastSentAt > 30000) sendLoc("📍 Moved, new location:", l)
    }

    private fun sendLoc(prefix: String, l: Location) {
        lastSent = l; lastSentAt = System.currentTimeMillis()
        sms("$prefix https://maps.google.com/?q=${l.latitude},${l.longitude} (±${l.accuracy.toInt()}m)")
    }

    private fun stopSos() {
        live = false; refine = false; coolDown()
        sms("✅ ${cfg().optString("name")} marked themselves safe. Live sharing stopped.")
        onStartCommand(null, 0, 0)
    }

    private fun sms(text: String) {
        val m = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val ph = cfg().optJSONArray("phones") ?: return
        for (k in 0 until ph.length()) try { m.sendMultipartTextMessage(ph.getString(k), null, m.divideMessage(text), null, null) } catch (_: Exception) {}
    }
    private fun cfg() = try { JSONObject(getSharedPreferences("pathra", 0).getString("cfg", "{}")!!) } catch (_: Exception) { JSONObject() }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { unregisterReceiver(rx); getSystemService(SensorManager::class.java).unregisterListener(this) }
}
