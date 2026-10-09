package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import proof.*
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

class ProofService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val gate = SweepGate()
    private lateinit var db: ProofDb
    private var busy = false
    private var lastPage = ""
    private var failedScrolls = 0
    private var active = emptySet<String>()
    private var lastDate = ""
    private var lastReadySeen = 0L

    override fun onServiceConnected() { db = ProofDb(this); syncMode() }
    private fun syncMode() {
        val selected = getSharedPreferences("control", MODE_PRIVATE).getString("sweep", null)
        if (selected != null && gate.date != selected) {
            gate.start(selected); busy = false; failedScrolls = 0; lastPage = ""
        } else if (selected == null && gate.mode == PageKind.HISTORY) {
            gate.stop(); busy = false; failedScrolls = 0
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        syncMode()
        if (event?.packageName?.toString() != "com.grab.merchant" || busy) return
        val root = rootInActiveWindow ?: return
        val metrics = resources.displayMetrics
        val screen = ScreenReader.read(root, metrics.widthPixels, metrics.heightPixels)
        if (!gate.allows(screen.page.kind)) return
        if (gate.mode == PageKind.READY) observeReady(screen)
        else observeHistory(screen)
    }
    private fun today() = LocalDate.now(ZoneId.of("Asia/Bangkok")).toString()
    private fun observeReady(screen: Screen) {
        val day = today()
        if (day != lastDate) { active = emptySet(); lastDate = day }
        val existing = db.ready(day).toMutableList()
        val now = System.currentTimeMillis()
        // Short transient disappearance does not create a second instance.
        if (now - lastReadySeen > 120_000) active = emptySet()
        val rows = screen.views.map { view ->
            val context = "${view.card.top / 60}:${view.card.bottom / 60}"
            Logic.instance(existing, day, view.card.gf, now, context, active).also {
                if (existing.none { old -> old.id == it.id }) { db.putReady(it); existing += it }
            }
        }
        active = rows.map { it.id }.toSet(); lastReadySeen = now
        val pending = Logic.needCapture(screen.page.cards, rows)
        getSharedPreferences("control",MODE_PRIVATE).edit()
            .putInt("visible",screen.views.size).putInt("pending",pending.size).apply()
        if (pending.isEmpty()) return
        capture(screen, "READY") { image ->
            if (image != null) pending.forEach { row ->
                if (screen.views.any { it.card.gf == row.gf }) db.putReady(row.copy(image=image))
            }
            // Failed rows stay Pending and can retry on the next content event.
            if (image == null) handler.postDelayed({ rootInActiveWindow?.let {
                val m=resources.displayMetrics; observeReady(ScreenReader.read(it,m.widthPixels,m.heightPixels))
            } },1500)
        }
    }
    private fun observeHistory(screen: Screen) {
        val day = gate.date ?: return
        if (screen.page.kind != PageKind.HISTORY) return
        val existing = db.history(day)
        val signature = screen.views.joinToString(";") { it.card.signature + ":" + it.bounds.top }
        val header = screen.page.header
        if (db.headerImage(day) == null && header?.consistent != true) {
            getSharedPreferences("control",MODE_PRIVATE).edit()
                .putString("status","รอยอดหัวหน้า History: เลื่อน Grab ขึ้นบนสุด").apply()
            return
        }
        val needsHeader = header?.consistent == true && db.headerImage(day) == null
        val unseen = screen.views.filter { view ->
            existing.none { it.card.signature == view.card.signature && it.image != null }
        }
        if (needsHeader || unseen.isNotEmpty()) {
            unseen.forEach { view ->
                if (existing.none { it.card.signature == view.card.signature })
                    db.putHistory(History("$day:${view.card.signature}",day,view.card,System.currentTimeMillis(),null))
            }
            capture(screen, "HISTORY") { image ->
                if (image == null) {
                    db.putHeader(day,header ?: Header(null,null,null),db.headerImage(day))
                    db.setStuck(day,true)
                    return@capture
                }
                if (needsHeader) db.putHeader(day,header!!,image)
                else if (header?.consistent == true && db.header(day)?.consistent != true)
                    db.putHeader(day,header,db.headerImage(day))
                db.setStuck(day,false)
                unseen.forEach { view ->
                    val old = existing.find { it.card.signature == view.card.signature }
                    val id = old?.id ?: "$day:${view.card.signature}"
                    db.putHistory(History(id,day,view.card,System.currentTimeMillis(),image))
                }
                scrollOne(screen,signature)
            }
        } else scrollOne(screen,signature)
    }
    private fun scrollOne(screen: Screen, before: String) {
        if (gate.mode != PageKind.HISTORY) return
        val day = gate.date ?: return
        val audit = Logic.audit(db.header(day),db.history(day),db.stuck(day),db.headerImage(day)!=null)
        if (audit.complete) {
            getSharedPreferences("control",MODE_PRIVATE).edit().putString("status","ครบตามยอดหัวหน้า").apply()
            return
        }
        if (screen.scroller == null) {
            db.setStuck(day,true)
            getSharedPreferences("control",MODE_PRIVATE).edit()
                .putString("status","เลื่อนไม่ได้: ${audit.rows}/${audit.header?.total ?: "UNKNOWN"}").apply()
            return
        }
        val m=resources.displayMetrics
        val path=Path().apply {
            moveTo(m.widthPixels*0.5f,m.heightPixels*0.73f)
            lineTo(m.widthPixels*0.5f,m.heightPixels*0.43f)
        }
        val gesture=GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path,0,420)).build()
        busy=true
        val issued=dispatchGesture(gesture,object: GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ busy=false; inspectAfterScroll(before) },500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                busy=false; db.setStuck(day,true)
            }
        },handler)
        if (!issued) { busy=false; db.setStuck(day,true) }
    }
    private fun inspectAfterScroll(before: String) {
        handler.post({
            if (gate.mode != PageKind.HISTORY || busy) return@post
            val day = gate.date ?: return@post
            val root=rootInActiveWindow ?: return@post
            val m=resources.displayMetrics
            val next=ScreenReader.read(root,m.widthPixels,m.heightPixels)
            val after=next.views.joinToString(";") { it.card.signature + ":" + it.bounds.top }
            failedScrolls = if (next.page.kind == PageKind.HISTORY && after == before) failedScrolls+1 else 0
            if (failedScrolls >= 3) {
                db.setStuck(day,true)
                getSharedPreferences("control",MODE_PRIVATE).edit()
                    .putString("status","ติดขัด: ภาพหน้าเดิมหลังเลื่อน 3 ครั้ง").apply()
            } else if (next.page.kind == PageKind.HISTORY) observeHistory(next)
        })
    }
    private fun capture(before: Screen, role: String, done: (String?) -> Unit) {
        if (busy) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY,executor,object: TakeScreenshotCallback {
            override fun onFailure(errorCode: Int) {
                busy = false; done(null)
            }
            override fun onSuccess(result: ScreenshotResult) {
                val buffer=result.hardwareBuffer
                var saved: String? = null
                try {
                    val root=rootInActiveWindow
                    val m=resources.displayMetrics
                    val after=root?.let { ScreenReader.read(it,m.widthPixels,m.heightPixels) }
                    // Do not attach a screenshot after a tab or card transition.
                    val valid=after != null && after.page.kind == before.page.kind &&
                        before.views.all { first -> after.views.any { it.card.gf == first.card.gf &&
                            it.bounds.top == first.bounds.top && it.card.delayed == first.card.delayed } }
                    if (valid) {
                        val bitmap=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                        if (bitmap != null) {
                            val dir=File(filesDir,"readyproof-v2").apply { mkdirs() }
                            val file=File(dir,"${role}_${System.currentTimeMillis()}_${System.nanoTime()}.jpg")
                            val temp=File(dir,file.name+".tmp")
                            FileOutputStream(temp).use { stream ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,stream))
                                stream.fd.sync()
                            }
                            check(temp.renameTo(file))
                            saved=file.absolutePath
                        }
                    }
                } catch (_: Exception) { saved=null }
                finally { buffer.close(); busy=false }
                done(saved)
            }
        })
    }
    override fun onInterrupt() { busy=false }
}
