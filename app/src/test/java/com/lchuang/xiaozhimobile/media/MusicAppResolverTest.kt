package com.lchuang.xiaozhimobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAppResolverTest {
    private val netease = MusicApp("com.netease.cloudmusic", "网易云音乐", MusicAppKind.NETEASE)
    private val qishui = MusicApp("com.luna.music", "汽水音乐", MusicAppKind.QISHUI)
    private val kugou = MusicApp("com.kugou.android", "酷狗音乐", MusicAppKind.KUGOU)
    private val qq = MusicApp("com.tencent.qqmusic", "QQ音乐", MusicAppKind.QQ_MUSIC)

    @Test
    fun explicitAppOverridesSavedDefaultAndActiveSession() {
        val resolver = resolver(
            installed = listOf(netease, qishui),
            saved = qishui.packageName,
            active = netease,
        )

        val result = resolver.resolve(explicit = netease.packageName)

        assertEquals(MusicResolutionSource.EXPLICIT, result.source)
        assertEquals(netease, result.app)
    }

    @Test
    fun validSavedDefaultWinsBeforeActiveSession() {
        val resolver = resolver(
            installed = listOf(netease, qishui),
            saved = qishui.packageName,
            active = netease,
        )

        val result = resolver.resolve()

        assertEquals(MusicResolutionSource.SAVED_DEFAULT, result.source)
        assertEquals(qishui, result.app)
    }

    @Test
    fun missingSavedDefaultIsClearedBeforeFallback() {
        var saved = "com.example.removed-music"
        val resolver = MusicAppResolver(
            installedApps = { listOf(netease, qishui) },
            savedDefaultPackage = { saved },
            persistDefaultPackage = { saved = it },
            activeMediaSession = { null },
        )

        val result = resolver.resolve()

        assertEquals(MusicResolutionSource.USER_CHOICE_REQUIRED, result.source)
        assertEquals(listOf(netease, qishui), result.candidates)
        assertTrue(result.repairedMissingDefault)
        assertEquals("", saved)
    }

    @Test
    fun activeMediaSessionIsUsedBeforeAskingAmongMultipleApps() {
        val resolver = resolver(
            installed = listOf(netease, qishui),
            active = qishui,
        )

        val result = resolver.resolve()

        assertEquals(MusicResolutionSource.ACTIVE_MEDIA_SESSION, result.source)
        assertEquals(qishui, result.app)
    }

    @Test
    fun oneInstalledAppIsSelectedAndMultipleAppsRequireChoice() {
        val single = resolver(installed = listOf(kugou)).resolve()
        val multiple = resolver(installed = listOf(qq, netease)).resolve()

        assertEquals(MusicResolutionSource.SINGLE_INSTALLED, single.source)
        assertEquals(kugou, single.app)
        assertEquals(MusicResolutionSource.USER_CHOICE_REQUIRED, multiple.source)
        assertEquals(listOf(qq, netease), multiple.candidates)
    }

    @Test
    fun explicitUnknownDoesNotSilentlyFallBack() {
        val result = resolver(installed = listOf(netease), active = netease)
            .resolve(explicit = "com.example.not-installed")

        assertEquals(MusicResolutionSource.EXPLICIT_UNAVAILABLE, result.source)
        assertEquals(null, result.app)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun userSelectionPersistsOnlyAnInstalledMusicPackage() {
        var saved = ""
        val resolver = MusicAppResolver(
            installedApps = { listOf(netease, qq) },
            savedDefaultPackage = { saved },
            persistDefaultPackage = { saved = it },
        )

        assertTrue(resolver.saveDefault(qq))
        assertEquals(qq.packageName, saved)
        assertFalse(resolver.saveDefault(MusicApp("com.example.fake", "假音乐", MusicAppKind.OTHER)))
        assertEquals(qq.packageName, saved)
    }

    @Test
    fun installedRecognitionIncludesKnownAndClearlyNamedMusicAppsOnly() {
        assertEquals(
            MusicAppKind.NETEASE,
            MusicApp.fromInstalled("com.netease.cloudmusic", "云音乐")?.kind,
        )
        assertEquals(
            MusicAppKind.OTHER,
            MusicApp.fromInstalled("com.example.musicplayer", "Pocket Music")?.kind,
        )
        assertEquals(null, MusicApp.fromInstalled("com.example.notes", "记事本"))
    }

    private fun resolver(
        installed: List<MusicApp>,
        saved: String = "",
        active: MusicApp? = null,
    ) = MusicAppResolver(
        installedApps = { installed },
        savedDefaultPackage = { saved },
        activeMediaSession = { active },
    )
}
