package com.studiospark.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/** Pumps the paused Robolectric main looper while background (Dispatchers.Default) work finishes in real time. */
internal fun pumpUntil(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); if (cond()) return true; Thread.sleep(10) }
    return cond()
}
internal fun pumpFor(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(10) } }
internal fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    .setComponent(ComponentName("com.studiospark.app", "com.studiospark.app.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
internal class FreshInstallRule : org.junit.rules.ExternalResource() {
    private fun wipe() { val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(ctx.filesDir, "session").deleteRecursively(); ctx.getSharedPreferences("studiospark", 0).edit().clear().commit() }
    /** Robolectric makes a new Application per test, but AndroidViewModelFactory caches the first one in a static; drop it so each test's ViewModel uses its own app. */
    private fun resetViewModelFactory() { runCatching { val c = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java
        c.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == c }.forEach { it.isAccessible = true; it.set(null, null) } } }
    override fun before() { resetViewModelFactory(); wipe() }
    // the app saves edits ~0.8 s after the last change (by design); let a pending save from this test land, then remove it
    // (the delay runs on the main-looper clock, so advance it, then give the IO write time to finish)
    override fun after() { runCatching { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3)) }; Thread.sleep(500); wipe() }
}
internal val SRC_MAIN = File("src/main").takeIf { it.exists() } ?: File("app/src/main")

/**
 * One test per failure mode from Lucas's debug prompt for "icon tap bounces to the home screen, bubble works".
 * 1) unhandled exception in MainActivity.onCreate on a normal launcher start
 * 2) null reference in a view binding
 * 3) a resource that exists only under a full-screen layout qualifier
 * 4) a permission or window-flag check that fails outside bubble / multi-window mode
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LaunchFailureModeTests {
    val compose = createAndroidComposeRule<MainActivity>()
    /** Every test starts as a fresh install: no saved session or settings from an earlier test. */
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)

    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java]

    @Test fun failureMode1_noUnhandledExceptionInOnCreateOnLauncherStart() {
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        try {
            // exactly what the launcher sends on an icon tap: ACTION_MAIN + CATEGORY_LAUNCHER, new task, fullscreen
            val ctrl = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup()
            val a = ctrl.get()
            val vm = ViewModelProvider(a)[StudioVM::class.java]
            assertSame("ViewModel bound to a stale Application", a.application, vm.getApplication())
            assertTrue("startup never finished", pumpUntil { vm.ready })
            pumpFor(500)
            assertFalse("activity finished itself on launch", a.isFinishing)
            assertTrue(a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            assertTrue("uncaught exceptions during launch: $errors", errors.isEmpty())
            ctrl.pause().stop().destroy()
        } finally { Thread.setDefaultUncaughtExceptionHandler(prev) }
    }

    @Test fun failureMode2_noViewBindingsAndFreshStartHasNoNulls() {
        // The UI is Jetpack Compose: no layouts, no ViewBinding/DataBinding, no findViewById that could return null.
        val gradle = File(SRC_MAIN, "../../build.gradle.kts").readText()
        assertFalse(Regex("""viewBinding\s*=\s*true|dataBinding\s*=\s*true""").containsMatchIn(gradle))
        assertFalse("layout resources exist", File(SRC_MAIN, "res").listFiles()!!.any { it.name.startsWith("layout") })
        val code = SRC_MAIN.walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(code.contains("findViewById")); assertFalse(Regex("""import .*databinding\.""").containsMatchIn(code)); assertFalse(code.contains("setContentView("))
        // Fresh install (no saved project): startup completes, state is empty-but-valid, and MainScreen composes the basic studio.
        val vm = vm()
        compose.waitUntil(20_000) { vm.ready }
        val ctxDbg = ApplicationProvider.getApplicationContext<android.app.Application>(); val pj = File(ctxDbg.filesDir, "session/project.json")
        assertTrue("fresh install should have no tracks: ${vm.project.tracks.map { it.name }} name=${vm.project.name} file=${pj.exists()} files=${ctxDbg.filesDir} vmApp=${(vm.getApplication<android.app.Application>()).filesDir}", vm.project.tracks.isEmpty()); assertNull(vm.selectedTrackId); assertNull(vm.selectedTrack())
        assertNotNull(vm.mixer.snap); assertEquals(0, vm.rigs.builtCount())
        compose.onNodeWithText("Add instrument or gear").assertExists()
        compose.onNodeWithText("StudioSpark").assertExists()
    }

    @Test fun failureMode3_noResourceExistsOnlyUnderAQualifier() {
        val res = File(SRC_MAIN, "res"); val minSdk = 31
        // qualifiers that never need a default: densities (nearest density is used) and API levels <= minSdk (always match)
        fun harmless(q: String) = q in setOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi", "tvdpi") ||
            (q.startsWith("v") && q.drop(1).toIntOrNull()?.let { it <= minSdk } == true)
        val problems = mutableListOf<String>()
        val dirs = res.listFiles()!!.filter { it.isDirectory }
        fun names(d: File) = if (d.name.startsWith("values")) d.listFiles()!!.filter { it.extension == "xml" }.flatMap { f ->
            Regex("""<(string|style|color|dimen|bool|integer|array|string-array|plurals)\s+name="([^"]+)"""").findAll(f.readText()).map { "${it.groupValues[1]}/${it.groupValues[2]}" }.toList() }.toSet()
            else d.listFiles()!!.map { it.nameWithoutExtension.removeSuffix(".9") }.toSet()
        for (d in dirs) { val parts = d.name.split('-'); val type = parts[0]; val quals = parts.drop(1)
            if (quals.isEmpty() || quals.all(::harmless)) continue
            // a size/orientation/night/etc. qualifier: every resource here must also exist in an always-matching set
            val fallback = dirs.filter { it.name.split('-')[0] == type && it.name.split('-').drop(1).all(::harmless) }.flatMap { names(it) }.toSet()
            (names(d) - fallback).forEach { problems += "${d.name}/$it" } }
        assertTrue("qualifier-only resources: $problems", problems.isEmpty())
        // no full-screen/large/landscape-only directories at all
        assertTrue(dirs.none { d -> d.name.split('-').drop(1).any { Regex("""^(land|port|large|xlarge|sw\d+dp|w\d+dp|h\d+dp|long|notlong)$""").matches(it) } })
        // every resource the manifest needs at launch resolves without qualifiers (or with density-only sets)
        val manifest = File(SRC_MAIN, "AndroidManifest.xml").readText()
        Regex("""@(mipmap|drawable|string|style)/([A-Za-z0-9_.]+)""").findAll(manifest).forEach { m -> val type = m.groupValues[1]; val name = m.groupValues[2]
            val ok = dirs.filter { it.name.split('-')[0] == (if (type == "string" || type == "style") "values" else type) && it.name.split('-').drop(1).all(::harmless) }
                .any { d -> if (type == "string" || type == "style") names(d).contains("$type/$name") else names(d).contains(name) }
            assertTrue("@$type/$name used by the manifest is missing from the default/density sets", ok) }
        // the launcher icon exists at every density that ships
        dirs.filter { it.name.startsWith("mipmap-") }.forEach { assertTrue("${it.name} lacks ic_launcher", File(it, "ic_launcher.png").exists() || File(it, "ic_launcher.xml").exists()) }
        // and resolves through the real resource system
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertNotEquals(0, ctx.applicationInfo.icon); assertNotNull(ctx.getDrawable(ctx.applicationInfo.icon))
    }

    @Test fun failureMode4_noBubbleOrMultiWindowOrPermissionDependencyAtStartup() {
        // static: startup code never branches on bubble / multi-window / PiP and sets no window flags
        val code = SRC_MAIN.walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("isInMultiWindowMode", "isLaunchedFromBubble", "isInPictureInPictureMode", "onMultiWindowModeChanged", "addFlags(", "setFlags(", "requestWindowFeature", "FLAG_SECURE", "FLAG_SHOW_WHEN_LOCKED")
            .forEach { assertFalse("source uses $it", code.contains(it)) }
        val manifest = File(SRC_MAIN, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("resizeableActivity=\"false\"")); assertFalse(manifest.contains("allowEmbedded")); assertFalse(manifest.contains("documentLaunchMode"))
        // runtime: launcher start in a normal fullscreen window, mic NOT granted, no permission prompt at launch
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val ctrl = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).setup(); val a = ctrl.get()
        val vm = ViewModelProvider(a)[StudioVM::class.java]
        assertTrue(pumpUntil { vm.ready }); pumpFor(300)
        assertEquals(PackageManager.PERMISSION_DENIED, a.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertFalse(a.isInMultiWindowMode); assertFalse(a.isInPictureInPictureMode)
        assertFalse(a.isFinishing); assertTrue(a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertNull("a permission was requested at launch", shadowOf(a).lastRequestedPermission)
        assertFalse("mic opened at launch", vm.engine.inputActive)
        val cfg = a.resources.configuration; val dm = a.resources.displayMetrics
        assertTrue("window is not full display width", abs(dm.widthPixels - (cfg.screenWidthDp * dm.density)) <= dm.density * 2)
        ctrl.pause().stop().destroy()
    }
}
