package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginNoteTest {
    @Test
    fun aStoreThatRepublishesAndAStoreDevelopersUploadToAreToldApart() {
        assertEquals(OriginNote.REPUBLISHED, originNote(SourceTypes.APKPURE))
        assertEquals(OriginNote.REPUBLISHED, originNote(SourceTypes.TENCENT))
        assertEquals(OriginNote.STORE, originNote(SourceTypes.HUAWEI))
        assertEquals(OriginNote.STORE, originNote(SourceTypes.SAMSUNG))
        assertNull(originNote(SourceTypes.GITHUB))
        assertNull(originNote(SourceTypes.ITCHIO))
        assertNull(originNote(SourceTypes.TELEGRAM))
    }

    @Test
    fun theNoteSaysWhatTheFirstInstallDecidesUnlessAPinDoes() {
        assertEquals(R.string.origin_republished_first, originText(OriginNote.REPUBLISHED, pinned = false))
        assertEquals(R.string.origin_republished_pinned, originText(OriginNote.REPUBLISHED, pinned = true))
        assertEquals(R.string.origin_store_first, originText(OriginNote.STORE, pinned = false))
        assertEquals(R.string.origin_store_pinned, originText(OriginNote.STORE, pinned = true))
    }

    @Test
    fun aSourceIsNamedAsPeopleKnowIt() {
        assertEquals("APKPure", sourceName(SourceSpec(SourceTypes.APKPURE, "https://apkpure.com/a/b.c")))
        assertEquals("example.org", sourceName(SourceSpec(SourceTypes.HTML, "https://example.org/downloads")))
    }
}
