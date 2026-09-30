package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginNoteTest {
    @Test
    fun aStoreThatRepublishesAndASiteThatModifiesAreToldApart() {
        assertEquals(OriginNote.REPUBLISHED, originNote(SourceTypes.APKPURE))
        assertEquals(OriginNote.MODIFIED, originNote(SourceTypes.LITEAPKS))
        assertNull(originNote(SourceTypes.GITHUB))
        assertNull(originNote(SourceTypes.HUAWEI))
    }

    @Test
    fun aSourceIsNamedAsPeopleKnowIt() {
        assertEquals("APKPure", sourceName(SourceSpec(SourceTypes.APKPURE, "https://apkpure.com/a/b.c")))
        assertEquals("example.org", sourceName(SourceSpec(SourceTypes.HTML, "https://example.org/downloads")))
    }
}
