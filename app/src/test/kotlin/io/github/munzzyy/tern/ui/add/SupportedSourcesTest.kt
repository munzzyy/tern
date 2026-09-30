package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedSourcesTest {
    @Test
    fun everySourceIsListedOnceUnderItsKind() {
        val listed = sourcesByKind()
        assertEquals(SourceTypes.ALL.sorted(), listed.flatMap { it.second }.sorted())
        val byKind = listed.toMap()
        assertTrue(SourceTypes.GITHUB in byKind.getValue(SourceKind.FORGES))
        assertTrue(SourceTypes.HUAWEI in byKind.getValue(SourceKind.STORES))
        assertTrue(SourceTypes.APKPURE in byKind.getValue(SourceKind.MIRRORS))
        assertTrue(SourceTypes.TENCENT in byKind.getValue(SourceKind.MIRRORS))
        assertTrue(SourceTypes.ITCHIO in byKind.getValue(SourceKind.OTHER))
        assertTrue(SourceTypes.HTML in byKind.getValue(SourceKind.OTHER))
    }
}
