package com.azim.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.azim.app.data.AppDb
import com.azim.app.data.ChatMsg
import com.azim.app.data.Training
import com.azim.app.data.DailyTask
import com.azim.app.data.Dates
import com.azim.app.data.Goal
import com.azim.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.math.max

object Categories {
    val all = listOf(
        "Ders / Sınav", "Fizik / Bilim", "Tıp", "Ruhsal gelişim",
        "Bağımlılık bırakma", "Spor / Beden", "Dil öğrenme", "Yazılım", "Diğer"
    )
    val levels = listOf("Başlangıç", "Orta", "İleri")
}

/** Eğitim oturumunun konusu: bir göreve bağlı (taskId > 0) ya da serbest (taskId = 0). */
data class TrainingTarget(val taskId: Long, val goalId: Long, val title: String, val detail: String)

/** Görev üretimi ve içerik hazırlama; ekranlardan bağımsız çalışır (kilit ekranı da kullanır). */
object Repo {
    private val mutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun startTraining(ctx: Context, uid: Long, target: TrainingTarget, minutes: Int, intent: String) {
        val dao = AppDb.get(ctx).dao()
        val now = System.currentTimeMillis()
        dao.closeActiveTrainings(uid, now)
        val id = dao.insertTraining(
            Training(
                userId = uid, goalId = target.goalId, taskId = target.taskId,
                title = target.title, plannedMin = minutes, startedAt = now
            )
        )
        Prefs.get(ctx).startSession(target.taskId, minutes, id, target.title, target.detail, intent)
    }

    /** Süre doldu ve kullanıcı notunu yazdı: eğitim tamamlanır, bağlı görev kapanır. */
    suspend fun finishTraining(ctx: Context, note: String) {
        val prefs = Prefs.get(ctx)
        val dao = AppDb.get(ctx).dao()
        val now = System.currentTimeMillis()
        val tid = prefs.sessionTrainingId
        val taskId = prefs.sessionTaskId
        if (tid > 0) dao.finishTraining(tid, "done", now, note)
        if (taskId > 0) dao.completeTask(taskId, now, note)
        prefs.clearSession()
    }

    /** Eğitimi süre dolmadan bitirir. status: "quit" (kendi isteğiyle bıraktı) ya da "emergency". */
    suspend fun endTraining(ctx: Context, status: String) {
        val prefs = Prefs.get(ctx)
        val dao = AppDb.get(ctx).dao()
        val uid = prefs.userId
        val tid = prefs.sessionTrainingId
        val title = prefs.sessionTitle
        val started = prefs.sessionStartedAt
        val planned = ((prefs.sessionEndsAt - started) / 60_000).toInt()
        val attempts = prefs.sessionAttempts
        val now = System.currentTimeMillis()
        if (tid > 0) dao.finishTraining(tid, status, now, "")
        prefs.clearSession()
        if (status == "quit" && uid > 0 && started > 0) {
            val elapsed = ((now - started) / 60_000).toInt().coerceAtLeast(0)
            appScope.launch {
                val text = try {
                    Coach.quitFollowUp(ctx, uid, title, elapsed, planned, attempts)
                } catch (e: Exception) {
                    Coach.fallbackQuitFollowUp()
                }
                dao.insertChat(ChatMsg(userId = uid, role = "model", text = text, ts = System.currentTimeMillis()))
            }
        }
    }

    /** Bugün görevi olmayan her hedef için görev üretir. Uyarı metni döner (hata/yedek görev olduysa). */
    suspend fun ensureTodayTasks(ctx: Context, uid: Long): String? = mutex.withLock {
        val dao = AppDb.get(ctx).dao()
        val today = Dates.today()
        var warning: String? = null
        for (g in dao.goalsOnce(uid)) {
            if (dao.countForGoal(uid, g.id, today) > 0) continue
            val (tasks, w) = Coach.generateTasks(ctx, uid, g, today)
            dao.insertTasks(tasks)
            if (w != null) warning = w
        }
        warning
    }
}

object Coach {
    private const val CORE = "Sen \"Azim\" uygulamasının kişisel gelişim koçusun. Türkçe, kısa, net ve somut konuşursun. " +
        "Kullanıcının hedefine ulaşması için disiplini önemsersin. Tıbbi teşhis koymaz, ilaç ya da doz önermezsin."

    private const val SAFETY = "Sınırların her tonda geçerlidir: Kullanıcıya hakaret etmez, değersiz ya da ümitsiz olduğunu söylemez, " +
        "bedeni ya da kişiliği üzerinden alay etmez, tehdit etmez, aşağılamazsın; hedef her zaman davranıştır, kişi değil. " +
        "Bağımlılık ve ruhsal gelişim hedeflerinde nüksü ya da kötü günü asla utandırma aracı yapmaz, en fazla net tonda kalıp sorumluluk almaya çağırırsın. " +
        "Kullanıcı umutsuzluk, kendine zarar verme ya da ağır bir sıkıntı belirtisi gösterirse sertliği tamamen bırakır, sıcak ve sakin konuşur, " +
        "güvendiği biriyle konuşmasını, gerekirse 112'yi aramasını ya da bir uzmana başvurmasını önerirsin. " +
        "Aşırı diyet, uykusuzluk ya da kendini cezalandıran bir plan asla önermezsin."

    private val TONES = mapOf(
        1 to "Tonun: sıcak ve destekleyici. Aksatmayı yargılamadan sorar, küçük adımlar önerirsin.",
        2 to "Tonun: açık sözlü ve net. Mazeretleri nazikçe ama ısrarla sorgular, kısa ve doğrudan konuşursun.",
        3 to "Tonun: sert ve yüzleştiren, kavga eder gibi doğrudan. Mazeretleri tek tek çürütür, söz ile eylem arasındaki tutarsızlığı yüzüne vurursun. " +
            "Gerektiğinde kullanıcıyı aynaya bakıp kendine \"bu halimden memnun muyum?\" diye sormaya çağırırsın; aynı gidişle bir yıl sonra nerede olacağını canlı ve somut anlatır, " +
            "kullanıcının kendi hâline gerçekten üzülüp harekete geçmesini sağlarsın. Sert olan senin kararlılığındır."
    )

    /** Koçun sistem talimatı: kimlik + (isteğe bağlı) ton + güvenlik sınırları + kullanıcı profili. */
    fun persona(ctx: Context, uid: Long, withTone: Boolean = true): String {
        val prefs = Prefs.get(ctx)
        val p = prefs.profile(uid)
        val sb = StringBuilder(CORE)
        if (withTone) sb.append("\n").append(TONES[prefs.coachTone] ?: TONES.getValue(2))
        sb.append("\n").append(SAFETY)
        if (p.state.isNotBlank() || p.vision.isNotBlank() || p.obstacles.isNotBlank()) {
            sb.append("\nKullanıcı profili (kendi sözleriyle):")
            if (p.state.isNotBlank()) sb.append("\n- Şu anki durumu: ").append(p.state)
            if (p.vision.isNotBlank()) sb.append("\n- Olmak istediği: ").append(p.vision)
            if (p.obstacles.isNotBlank()) sb.append("\n- Onu engelleyenler: ").append(p.obstacles)
        }
        return sb.toString()
    }

    private fun fallbackTask(uid: Long, goal: Goal, today: String) = DailyTask(
        userId = uid, goalId = goal.id, date = today,
        title = "${goal.title}: odaklı çalışma",
        detail = "Telefonsuz ve kesintisiz çalış. Bitirince ne yaptığını bir iki cümleyle not et.",
        minutes = goal.dailyMinutes.coerceIn(10, 180)
    )

    suspend fun generateTasks(ctx: Context, uid: Long, goal: Goal, today: String): Pair<List<DailyTask>, String?> {
        if (!Prefs.get(ctx).hasKeys()) {
            return listOf(fallbackTask(uid, goal, today)) to
                "Gemini API anahtarı girilmemiş; yedek görev verildi. Ayarlar'dan anahtarı ekle."
        }
        return try {
            val dao = AppDb.get(ctx).dao()
            val since = LocalDate.now().minusDays(7).toString()
            val history = dao.tasksSince(uid, since).filter { it.goalId == goal.id && it.date < today }
            val lines = history.takeLast(20).joinToString("\n") {
                "${it.date} | ${it.title} (${it.minutes} dk) | ${if (it.done) "YAPILDI" else "YAPILMADI"}" +
                    if (it.note.isNotBlank()) " | not: ${it.note}" else ""
            }.ifBlank { "(henüz geçmiş yok)" }

            val prompt = """
                Hedef: ${goal.title}
                Kategori: ${goal.category}
                Seviye: ${goal.level}
                Günlük hedef süre: ${goal.dailyMinutes} dakika
                Bugün: $today

                Son 7 günün kaydı:
                $lines

                Bugün için 2 ile 4 arası görev üret. Toplam süre yaklaşık ${goal.dailyMinutes} dakika olsun.
                Hedef net değilse kullanıcı profilindeki "olmak istediği" şeyden yola çık ve oraya götüren somut bir plan kur.
                Görevler somut ve ölçülebilir olsun (ne yapılacağı ve ne zaman bitmiş sayılacağı belli).
                Kayıtta yapılmayan görevler varsa yükü akıllıca ayarla; hep yapılıyorsa bir kademe zorlaştır.
                Yalnızca şu JSON biçiminde yanıt ver:
                {"tasks":[{"title":"kısa başlık","detail":"ne yapılacak, 1-2 cümle","minutes":25}]}
            """.trimIndent()

            val raw = Gemini.generate(ctx, persona(ctx, uid, withTone = false), listOf(Turn("user", listOf(Part.Text(prompt)))), json = true)
            val tasks = parseTasks(raw, uid, goal, today)
            if (tasks.isEmpty()) throw GeminiException(0, "Görev listesi çözümlenemedi")
            tasks to null
        } catch (e: Exception) {
            listOf(fallbackTask(uid, goal, today)) to
                "Yapay zekâya ulaşılamadı (${e.message}); yedek görev verildi."
        }
    }

    private fun parseTasks(raw: String, uid: Long, goal: Goal, today: String): List<DailyTask> {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONObject(cleaned).getJSONArray("tasks")
        val out = mutableListOf<DailyTask>()
        for (i in 0 until minOf(arr.length(), 4)) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            out.add(
                DailyTask(
                    userId = uid, goalId = goal.id, date = today,
                    title = title,
                    detail = o.optString("detail").trim(),
                    minutes = o.optInt("minutes", 25).coerceIn(5, 120)
                )
            )
        }
        return out
    }

    /** Koçla sohbet. history en eski → en yeni sırada, son mesaj dahil değildir. */
    suspend fun chat(ctx: Context, uid: Long, history: List<Pair<String, String>>, userText: String): String {
        val dao = AppDb.get(ctx).dao()
        val goals = dao.goalsOnce(uid).joinToString("; ") { "${it.title} (${it.category}, ${it.level})" }
        val today = Dates.today()
        val tasksNow = dao.tasksSince(uid, today).filter { it.date == today }
            .joinToString("; ") { "${it.title} [${if (it.done) "yapıldı" else "bekliyor"}]" }
            .ifBlank { "henüz yok" }
        val system = persona(ctx, uid) +
            "\nKullanıcının hedefleri: $goals\nBugünkü görevleri: $tasksNow" +
            "\nYanıtların kısa olsun (en fazla birkaç kısa paragraf). Kullanıcı mazeret üretirse itiraz et, ama çözüm yolunu da göster."
        val turns = history.map { (role, text) -> Turn(role, listOf(Part.Text(text))) } +
            Turn("user", listOf(Part.Text(userText)))
        return Gemini.generate(ctx, system, turns).trim()
    }

    /** slot: 0 sabah, 1 öğle, 2 akşam. Bildirim metni üretir (en fazla 3 kısa cümle). */
    suspend fun reminder(ctx: Context, uid: Long, slot: Int): String {
        val dao = AppDb.get(ctx).dao()
        val today = Dates.today()
        val todayTasks = dao.tasksSince(uid, today).filter { it.date == today }
        val done = todayTasks.count { it.done }
        val total = todayTasks.size
        val recent = dao.tasksSince(uid, LocalDate.now().minusDays(3).toString()).filter { it.date < today }
        val goals = dao.goalsOnce(uid).joinToString("; ") { it.title }
        val tone = Prefs.get(ctx).coachTone

        val slotText = when (slot) {
            0 -> "Şu an sabah. Kullanıcıyı güne başlat: \"hadi bugün\" havasında, bugünkü görevlere ve neden önemli olduğuna değin. Dünkü ihmal varsa kısaca hatırlat."
            1 -> "Şu an öğle/öğleden sonra. Görevlerin durumuna bak: aksatıyorsa (geçmiş günlerde de yapılmayanlar varsa) bunu yüzüne vur ve şimdi bir görevi başlatmasını iste; iyi gidiyorsa kısaca takdir et ve devam ettir."
            else -> "Şu an akşam. Kullanıcıya şunu sor: \"Şu anki halinden memnun musun?\" " +
                "Görevler yapılmadıysa mazeretleri sorgula" +
                (if (tone == 3) ", memnunsa bu uygulamaya ve hedefe neden ihtiyacı olduğunu sorgula (\"memnunsan sil bu uygulamayı\" tonunda), aynaya bakıp kendine dürüstçe sormasını söyle" else "") +
                "; yapıldıysa kısaca takdir et ve yarın için bir şey sor."
        }

        val prompt = """
            Hedefler: $goals
            Bugün: $done / $total görev yapıldı.
            Son 3 gün: ${recent.count { it.done }} görev yapıldı, ${recent.count { !it.done }} görev yapılmadı.

            $slotText
            En fazla 3 kısa cümle yaz. Düz metin, madde işareti ya da emoji yok. Bir bildirim olarak gösterilecek.
        """.trimIndent()

        return Gemini.generate(ctx, persona(ctx, uid), listOf(Turn("user", listOf(Part.Text(prompt))))).trim()
    }

    fun fallbackReminder(slot: Int): String = when (slot) {
        0 -> "Günaydın. Bugünün görevleri hazır; ilk adımı şimdi at, sonrası kolaylaşır."
        1 -> "Gün yarıyı geçti. Görevlerin hâlâ bekliyor; şimdi bir tanesini başlat."
        else -> "Akşam oldu. Bugünkü halinden memnun musun? Değilsen yarını bekleme, şimdi bir görevi kapat."
    }

    /** Kullanıcı eğitimi bırakmak / uygulamaları açmak istediğinde gösterilen öğüt. Karar yine kullanıcıdadır. */
    suspend fun quitAdvice(
        ctx: Context, uid: Long, title: String, elapsedMin: Int, remainingMin: Int, attempts: Int, intent: String
    ): String {
        val prompt = """
            Kullanıcı eğitim oturumunu (konu: $title) bırakmak ya da kapalı uygulamaları açmak istiyor.
            Geçen süre: $elapsedMin dk, kalan süre: $remainingMin dk. Bu oturumda kaçma girişimi: $attempts.
            Niyeti: ${intent.ifBlank { "(yazmamış)" }}
            Ona bir öğüt ver: bırakmayı yasaklama, karar onun; ama şimdi bırakmanın bedelini ve kalan süreyle ne kazanacağını somut söyle,
            küçük bir pazarlık öner (ör. 10 dakika daha dayan, sonra karar ver).
            En fazla 3 kısa cümle. Düz metin, madde işareti ya da emoji yok.
        """.trimIndent()
        return Gemini.generate(ctx, persona(ctx, uid), listOf(Turn("user", listOf(Part.Text(prompt))))).trim()
    }

    fun fallbackQuitAdvice(tone: Int): String = when (tone) {
        1 -> "Zor bir an olabilir, ama başladığın işi yarıda bırakmak sonra daha ağır gelir. Birkaç dakika daha dayan, sonra karar ver. Karar yine senin."
        2 -> "Şu an açmak istediğin şey bir saat sonra da aynı yerde duracak. Kalan süreyi bitir, sonra istediğini aç. Yine de bırakmak istiyorsan bu senin kararın."
        else -> "Daha ilk zorlukta kaçıyorsun ve bunu sen seçiyorsun. Bu sefer yarıda bırakmanın kime yaradığını düşün. Yine de bırakacaksan bırak, ama nedenini kendine dürüstçe söyle."
    }

    suspend fun quitFollowUp(ctx: Context, uid: Long, title: String, elapsedMin: Int, plannedMin: Int, attempts: Int): String {
        val prompt = """
            Kullanıcı "$title" eğitimini $plannedMin dakikanın $elapsedMin. dakikasında yarıda bıraktı. Kaçma girişimi: $attempts.
            Neden bıraktığını sor ve bir sonraki sefer için küçük, uygulanabilir bir şey iste. Tonun ayarlıdır; hakaret etme.
            En fazla 3 kısa cümle. Düz metin.
        """.trimIndent()
        return Gemini.generate(ctx, persona(ctx, uid), listOf(Turn("user", listOf(Part.Text(prompt))))).trim()
    }

    fun fallbackQuitFollowUp(): String =
        "Eğitimi yarıda bıraktın. Neden? Dürüstçe yaz; bir sonraki sefer için süreyi kısaltalım ya da başka bir saat seçelim."

    /** Haftalık fotoğraf + haftalık performans özetiyle geri bildirim üretir. */
    suspend fun analyzePhoto(ctx: Context, uid: Long, goal: Goal, photo: File, previousFeedback: String?): String {
        val dao = AppDb.get(ctx).dao()
        val since = LocalDate.now().minusDays(6).toString()
        val week = dao.tasksSince(uid, since).filter { it.goalId == goal.id }
        val done = week.count { it.done }
        val minutes = week.filter { it.done }.sumOf { it.minutes }
        val b64 = withContext(Dispatchers.Default) { prepareImage(photo) }

        val prompt = """
            Hedef: ${goal.title} (${goal.category}, ${goal.level})
            Son 7 gün: ${week.size} görevden $done tanesi tamamlandı, toplam $minutes dakika çalışıldı.
            Önceki haftanın geri bildirimi: ${previousFeedback ?: "(ilk rapor)"}

            Ekteki fotoğraf kullanıcının bu haftaki gelişim fotoğrafı (ör. çalışma masası, defter, el yazısı, duruş, ilerleme kanıtı).
            Fotoğrafta gerçekten görebildiklerini nesnel biçimde yorumla; görmediğin şeyi uydurma ve tıbbi teşhis koyma.
            Şu başlıklarla, kısa ve Türkçe yaz:
            1) Bu hafta gözlemlediğin gelişim
            2) Zayıf kalan noktalar
            3) Gelecek hafta için 3 somut ayar
        """.trimIndent()

        val turns = listOf(Turn("user", listOf(Part.Text(prompt), Part.Image("image/jpeg", b64))))
        return Gemini.generate(ctx, persona(ctx, uid), turns).trim()
    }

    private fun prepareImage(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw GeminiException(0, "Fotoğraf okunamadı")
        val longest = max(bmp.width, bmp.height)
        val scaled = if (longest > 1280) {
            val r = 1280f / longest
            Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
