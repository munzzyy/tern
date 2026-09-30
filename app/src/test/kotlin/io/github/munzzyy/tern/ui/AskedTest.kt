package io.github.munzzyy.tern.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Another app or a web page cannot start Update all, Add or a check without the person. */
class AskedTest {
    @Test
    fun ternsOwnShortcutsDoWhatTheySay() {
        assertEquals(Asked.UpdateAll, asked(ACTION_UPDATE_ALL, null, ours = true))
        assertEquals(Asked.Add, asked(ACTION_ADD, null, ours = true))
        assertEquals(Asked.Check(RefreshLink.All), asked(ACTION_CHECK, null, ours = true))
    }

    @Test
    fun anotherAppSendingTheSameIntentGetsNothingDone() {
        // As in am start -n io.github.munzzyy.tern/.MainActivity -a io.github.munzzyy.tern.action.UPDATE_ALL
        assertNull(asked(ACTION_UPDATE_ALL, null, ours = false))
        assertNull(asked(ACTION_ADD, null, ours = false))
        assertNull(asked(ACTION_CHECK, null, ours = false))
    }

    @Test
    fun aRefreshLinkFromOutsideIsAskedAboutFirst() {
        assertEquals(Asked.Confirm(RefreshLink.All), asked(ACTION_VIEW, "tern://refresh", ours = false))
        assertEquals(Asked.Confirm(RefreshLink.One("org.example")), asked(ACTION_VIEW, "obtainium://refresh?id=org.example", ours = false))
        assertNull(asked(ACTION_VIEW, "tern://add?url=https%3A%2F%2Fexample.org", ours = false))
        assertNull(asked(ACTION_SEND, null, ours = false))
    }

    @Test
    fun aLinkCannotKeepTernChecking() {
        assertTrue(linkCheckAllowed(null, 1_000))
        assertFalse(linkCheckAllowed(1_000, 1_000 + LINK_CHECK_GAP_MS - 1))
        assertTrue(linkCheckAllowed(1_000, 1_000 + LINK_CHECK_GAP_MS))
    }

    @Test
    fun theShortcutsComeThroughAnAliasNoOtherAppCanStart() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val alias = Regex("""<activity-alias\s+android:name="\.Shortcuts"[^>]*>""").find(manifest)!!.value
        assertTrue(alias, "android:exported=\"false\"" in alias)
        assertTrue(alias, "android:targetActivity=\".MainActivity\"" in alias)
        assertTrue(SHORTCUTS.endsWith(".Shortcuts"))
        // The main activity has to stay exported for the launcher, so it is the alias that is trusted, never the main one.
        assertFalse(Regex("""<intent-filter>(?:(?!</intent-filter>).)*action\.(UPDATE_ALL|ADD|CHECK)""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(manifest))
    }
}
