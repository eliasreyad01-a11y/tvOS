package com.mytv.tvos

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.flyfishxu.kadb.Kadb

/**
 * TV Manager, built into the launcher: the TV talks to its own ADB daemon over the network (127.0.0.1, "wireless debugging" or
 * "network debugging" in Developer options) and uses it to stop, disable, enable or remove apps. Everything that needs no ADB
 * (the app list, memory, storage) is read straight from Android.
 */
object TvManager {
    class AppRow(val pkg: String, val label: String, val system: Boolean, val enabled: Boolean)

    /** Apps that are never touched: removing them would leave the TV unusable. */
    val PROTECTED = setOf("android", "com.android.systemui", "com.android.settings", "com.android.providers.settings",
        "com.android.shell", "com.mytv.tvos", "com.android.packageinstaller", "com.google.android.packageinstaller")

    private const val HOST = "127.0.0.1"
    @Volatile private var kadb: Kadb? = null
    @Volatile private var appCache: List<AppRow>? = null

    val connected: Boolean get() = kadb != null

    private fun prep(ctx: Context) {
        // Kadb keeps its ADB identity (the key the TV asks you to trust once) under the user's home directory.
        System.setProperty("user.home", ctx.filesDir.absolutePath)
    }

    fun port(p: android.content.SharedPreferences) = p.getInt("tvm_port", 5555)

    /** Connects to the TV's own ADB. Returns null when it worked, otherwise a short reason. Run off the main thread. */
    fun connect(ctx: Context, port: Int): String? {
        prep(ctx)
        disconnect()
        return try {
            val k = Kadb.create(HOST, port)
            val r = k.shell("echo ok")
            if (r.exitCode != 0 || !r.output.contains("ok")) { try { k.close() } catch (_: Exception) {}; "The TV refused the connection" }
            else { kadb = k; null }
        } catch (e: Throwable) { e.message?.take(80) ?: e.javaClass.simpleName }
    }

    /** Pairing with a code (Android 11+ Wireless debugging › Pair device with pairing code). Returns null on success. */
    fun pair(ctx: Context, port: Int, code: String): String? {
        prep(ctx)
        return try { kotlinx.coroutines.runBlocking { Kadb.pair(HOST, port, code) }; null } catch (e: Throwable) { e.message?.take(80) ?: e.javaClass.simpleName }
    }

    fun disconnect() { try { kadb?.close() } catch (_: Exception) {}; kadb = null }

    /** Runs one shell command. Pair(exit code, text); exit code -1 when there is no connection. */
    fun sh(cmd: String): Pair<Int, String> {
        val k = kadb ?: return -1 to "Not connected"
        return try { val r = k.shell(cmd); r.exitCode to r.output.trim() } catch (e: Throwable) { kadb = null; -1 to (e.message ?: "Connection lost") }
    }

    // ------------------------------------------------------------ apps (no ADB needed)

    fun invalidate() { appCache = null }

    /** The cached list, or null while it is being read in the background (then [onReady] runs on that thread). */
    fun apps(ctx: Context, onReady: () -> Unit): List<AppRow>? {
        appCache?.let { return it }
        val app = ctx.applicationContext
        Thread {
            val pm = app.packageManager
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            appCache = try {
                pm.getInstalledApplications(flags)
                    .filter { it.packageName != app.packageName }
                    .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0, it.enabled) }
                    .sortedBy { it.label.lowercase() }
            } catch (_: Exception) { emptyList() }
            onReady()
        }.start()
        return null
    }

    // ------------------------------------------------------------ the change log (so everything can be put back)

    private const val LOG = "tvm_log"

    fun logged(p: android.content.SharedPreferences): List<Pair<String, String>> =
        (p.getStringSet(LOG, emptySet()) ?: emptySet()).mapNotNull { s -> s.split("|").takeIf { it.size == 2 }?.let { it[0] to it[1] } }.sortedBy { it.first }

    fun log(p: android.content.SharedPreferences, pkg: String, action: String?) {
        val set = (p.getStringSet(LOG, emptySet()) ?: emptySet()).filterNot { it.startsWith("$pkg|") }.toMutableSet()
        if (action != null) set.add("$pkg|$action")
        p.edit().putStringSet(LOG, set).apply()
    }

    /** Puts one app back: turns it on again, or reinstalls the system copy that was removed for this user. */
    fun restore(pkg: String, action: String): Pair<Int, String> =
        if (action == "removed") sh("cmd package install-existing $pkg") else sh("pm enable $pkg")

    // ------------------------------------------------------------ default home screen

    class HomeApp(val pkg: String, val component: String, val label: String)

    private fun homeIntent() = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)

    /** Every launcher installed on the TV (this one and the original Android one among them). */
    fun homes(ctx: Context): List<HomeApp> {
        val pm = ctx.packageManager
        return try {
            pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_ALL).map {
                HomeApp(it.activityInfo.packageName, "${it.activityInfo.packageName}/${it.activityInfo.name}", it.loadLabel(pm).toString())
            }.distinctBy { it.component }
        } catch (_: Exception) { emptyList() }
    }

    /** The launcher that opens on the Home key now, or null when Android would still ask. */
    fun currentHome(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }
    } catch (_: Exception) { null }

    /** Makes [h] the launcher of the Home key (it is turned on first in case it had been disabled). */
    fun setHome(h: HomeApp): Pair<Int, String> = sh("pm enable ${h.pkg}; cmd package set-home-activity ${h.component}")

    // ------------------------------------------------------------ memory and storage

    fun fmt(bytes: Long): String = if (bytes >= 1L shl 30) String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0) else "${bytes shr 20} MB"

    fun memoryText(ctx: Context): String {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return "${fmt(mi.availMem)} / ${fmt(mi.totalMem)}"
    }

    fun storageText(): String = try {
        val s = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        "${fmt(s.availableBytes)} / ${fmt(s.totalBytes)}"
    } catch (_: Exception) { "" }

    // ------------------------------------------------------------ default launcher

    class Home(val label: String, val component: android.content.ComponentName, val current: Boolean)

    /** Every app that can be the home screen, and which one is the default now. */
    fun homes(ctx: Context): List<Home> {
        val pm = ctx.packageManager
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val cur = pm.resolveActivity(i, 0)?.activityInfo?.packageName
        return pm.queryIntentActivities(i, 0).map {
            Home(it.loadLabel(pm).toString(), android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.activityInfo.packageName == cur)
        }.distinctBy { it.component.packageName }
    }

    /** Makes [c] the default home screen (needs the ADB link). Pair(exit code, text). */
    fun setHome(c: android.content.ComponentName): Pair<Int, String> = sh("cmd package set-home-activity --user 0 ${c.flattenToShortString()}")

    // ------------------------------------------------------------ free memory (works without ADB)

    private fun avail(ctx: Context): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return mi.availMem
    }

    /** Ends every background app. With the ADB link it also runs `am kill-all`. Returns the memory gained, in bytes. Off the main thread. */
    fun freeMemory(ctx: Context): Long {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val before = avail(app)
        if (connected) sh("am kill-all")
        for (a in app.packageManager.getInstalledApplications(0)) {
            if (a.packageName != app.packageName && a.packageName !in PROTECTED) try { am.killBackgroundProcesses(a.packageName) } catch (_: Exception) {}
        }
        Thread.sleep(900)
        return (avail(app) - before).coerceAtLeast(0)
    }
}
