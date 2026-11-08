package com.joassam.hud

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.tmapmobility.tmap.tmapsdk.edc.EDCConst
import com.tmapmobility.tmap.tmapsdk.edc.TmapEDCSDK

/**
 * 티맵 공식 연동 (TMAP EDC SDK).
 * 티맵 앱이 1초마다 보내는 실시간 안내(RGData)를 받아 HUD로 보낸다:
 * 회전 종류·거리, 다음 회전, 제한속도, 단속카메라, 남은 거리/시간.
 * 필요: SK open API appKey + TMAP API 상품(Premium). 인증 1회(=1건)마다 요금 발생 → 앱 실행 중 1번만 인증.
 * 참고 구현: github.com/endofday-hub/DayzHUD (TmapSource.kt)
 */
object TmapEdc {

    @JvmStatic @Volatile var state = "꺼짐"
    @JvmStatic @Volatile var lastData = 0L
    @JvmStatic @Volatile var authFailed = false

    @JvmStatic fun fresh() = System.currentTimeMillis() - lastData < 3000
    @JvmStatic fun ok() = !authFailed && (fresh() || state.startsWith("연결됨") || state.startsWith("안내"))

    private var starting = false
    private var keysLogged = false
    private val main = Handler(Looper.getMainLooper())

    private val endTimeout = Runnable {
        state = "연결됨 · 대기"
        sendEnded()
    }

    @JvmStatic
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val key = app.getSharedPreferences("hud", Context.MODE_PRIVATE).getString("tmap_key", "")?.trim().orEmpty()
        if (key.isEmpty()) { state = "키 없음"; return }
        try {
            if (TmapEDCSDK.isInitialized) { state = "연결됨 · 대기"; register(); return }
            if (starting) return
            starting = true
            authFailed = false
            state = "연결 중…"
            NavListenerService.log("티맵 공식 연동(EDC) 시작")
            val auth = TmapEDCSDK.Companion.EDCAuthData("", key, app.packageName, "", "")
            TmapEDCSDK.initialize(app, auth, work, false)
        } catch (t: Throwable) {
            starting = false
            state = "시작 실패"
            NavListenerService.log("EDC 시작 실패: $t")
        }
    }

    @JvmStatic
    fun stop() {
        starting = false
        main.removeCallbacks(endTimeout)
        runCatching {
            TmapEDCSDK.unregisterDataCallback()
            TmapEDCSDK.finish()
        }
        state = "꺼짐"
    }

    // 콜백이 이미 저장돼 있으면 SDK가 등록을 건너뛰므로 매번 해제 후 등록
    private fun register() {
        runCatching { TmapEDCSDK.unregisterDataCallback() }
        runCatching { TmapEDCSDK.registerDataCallback(data) }
    }

    private val work = object : TmapEDCSDK.Companion.EDCWorkListener {
        override fun onInitSuccessEDC() {
            starting = false
            state = "연결됨 · 대기"
            NavListenerService.log("티맵 공식 연동 성공")
            main.postDelayed({ register() }, 1000)
        }

        override fun onHostAppStarted() = register()

        override fun onFinishedEDC() {
            starting = false
            state = "꺼짐"
            sendEnded()
        }

        override fun onAuthAction(bundle: Bundle) {
            val a = bundle.getInt("authStatus")
            if (a == -1) {
                authFailed = true
                starting = false
                state = "인증 실패 (키·Premium 요금제 확인)"
            }
            if (a == 1) register()
            NavListenerService.log("EDC 인증 상태: $a")
        }

        override fun onRouteStarted(bundle: Bundle) {
            state = "안내 시작"
            register()
        }

        override fun onRouteFinished(bundle: Bundle) {
            state = "연결됨 · 대기"
            sendEnded()
        }

        override fun onResult(commandState: EDCConst.CommandState, o: Any) {}

        override fun onFail(commandState: EDCConst.CommandState, i: Int, s: String?) {
            if (s == "TMAP_IS_NOT_RUNNING") {
                state = "티맵 실행 대기"
                return
            }
            starting = false
            NavListenerService.log("EDC 실패 $commandState ($i) $s")
        }
    }

    private val data = object : TmapEDCSDK.Companion.TMAPDataListener {
        override fun onReceive(bundle: Bundle?) {
            val m = bundle?.let { toMap((it.get("EDCData") as? Bundle) ?: it) } ?: return
            if (!keysLogged) {
                keysLogged = true
                NavListenerService.log("티맵 실시간 안내 수신 시작 (항목 ${m.size}개)")
            }
            state = "안내 받는 중"
            lastData = System.currentTimeMillis()
            UdpSender.send(toNav(m).toJson())
            main.removeCallbacks(endTimeout)
            main.postDelayed(endTimeout, 3000)
        }

        override fun onEvent(eventData: EDCConst.EventData, bundle: Bundle?) {}
    }

    private fun sendEnded() {
        val d = NavData()
        d.active = false
        d.pkg = "edc"
        UdpSender.send(d.toJson())
    }

    private fun toNav(m: Map<String, Any?>): NavData {
        val first = sub(m, "firstTBTInfo")
        val second = sub(m, "secondTBTInfo")
        val sdi = sub(m, "firstSDIInfo")
        val d = NavData()
        d.pkg = "edc"
        val total = int(m, "nGoPosDist").takeIf { it > 0 } ?: int(m, "remainDistanceToDestinationInMeter")
        if (!bool(m, "hasTbtInfo") && total <= 0) {
            d.active = false
            return d
        }
        d.dir = RouteGuide.mapTurn(int(first, "nTBTTurnType")) ?: "STRAIGHT"
        d.dist = fmt(int(first, "nTBTDist"))
        d.text = str(first, "szTBTMainText").ifEmpty { str(first, "szCrossName") }
        d.sub = str(m, "destinationName")
        val nt = int(second, "nTBTTurnType")
        if (nt > 0) {
            d.nextDir = RouteGuide.mapTurn(nt) ?: "STRAIGHT"
            d.nextDist = int(second, "nTBTDist")
        }
        d.limit = int(m, "limitSpeed")
        if (bool(m, "showSDI") && sdi.isNotEmpty()) {
            d.sdiType = int(sdi, "nSdiType")
            d.sdiDist = int(sdi, "nSdiDist")
            val sl = int(sdi, "nSdiSpeedLimit")
            if (sl > 0) d.limit = sl
        }
        d.remainDist = total
        d.remainTime = int(m, "nGoPosTime").takeIf { it > 0 } ?: int(m, "remainTimeToDestinationInSec")
        d.navSpeed = int(m, "speedInKmPerHour")
        return d
    }

    private fun fmt(m: Int): String = when {
        m <= 0 -> ""
        m >= 1000 -> String.format(java.util.Locale.KOREA, "%.1fkm", m / 1000f)
        else -> "${m}m"
    }

    @Suppress("DEPRECATION")
    private fun toMap(b: Bundle): Map<String, Any?> = b.keySet().associateWith { b.get(it) }

    private fun sub(m: Map<String, Any?>, k: String): Map<String, Any?> = when (val v = m[k]) {
        is Bundle -> toMap(v)
        is Map<*, *> -> v.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }

    private fun int(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toInt() ?: 0
    private fun bool(m: Map<String, Any?>, k: String) = m[k] as? Boolean ?: false
    private fun str(m: Map<String, Any?>, k: String) = m[k] as? String ?: ""
}
