package io.github.munzzyy.stamp.core.handoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultipartTest {
    private val boundary = "----StampTestBoundary7MA4YWxk"

    private fun part(content: String, disposition: String = "form-data; name=\"file\"; filename=\"apps.json\"", type: String? = "application/json") =
        "--$boundary\r\nContent-Disposition: $disposition\r\n" + (if (type == null) "" else "Content-Type: $type\r\n") + "\r\n$content\r\n"

    private fun read(body: String) = Multipart.single(body.toByteArray(Charsets.ISO_8859_1), boundary, "file")

    @Test
    fun theBoundaryIsReadFromTheHeaderAsBrowsersWriteIt() {
        assertEquals("----WebKitFormBoundaryAbCd0123", Multipart.boundary("multipart/form-data; boundary=----WebKitFormBoundaryAbCd0123"))
        assertEquals("---------------------------1234567890", Multipart.boundary("multipart/form-data; boundary=---------------------------1234567890"))
        assertEquals("a'()+_,-./:=?z", Multipart.boundary("Multipart/Form-Data;boundary=\"a'()+_,-./:=?z\""))
        assertEquals("b".repeat(70), Multipart.boundary("multipart/form-data; boundary=" + "b".repeat(70)))
    }

    @Test
    fun aHeaderThatNamesNoUsableBoundaryIsRefused() {
        val not = listOf(
            null, "", "multipart/form-data", "multipart/form-data; boundary=", "multipart/form-data; boundary=\"\"",
            "multipart/mixed; boundary=abc", "application/json; boundary=abc", "multipart/form-data; boundary=a b",
            "multipart/form-data; boundary=a\"b", "multipart/form-data; boundary=a<b", "multipart/form-data; boundary=" + "b".repeat(71),
            "multipart/form-data; boundary=abc; boundary=def", "multipart/form-data; charset=utf-8; boundary=abc",
        )
        for (header in not) assertNull(header, Multipart.boundary(header))
    }

    @Test
    fun oneFileComesOutAsItWentIn() {
        val content = "{\"format\":\"stamp-export\"}\r\n--not the boundary\r\n"
        val read = read(part(content) + "--$boundary--\r\n")!!
        assertEquals("apps.json", read.fileName)
        assertArrayEquals(content.toByteArray(), read.content)
        assertArrayEquals(content.toByteArray(), read(part(content) + "--$boundary--")!!.content)
        assertArrayEquals(ByteArray(0), read(part("") + "--$boundary--\r\n")!!.content)
        assertEquals("apps.json", read(part("x", type = null) + "--$boundary--\r\n")!!.fileName)
    }

    @Test
    fun everyByteOfAFileIsKept() {
        val all = ByteArray(512) { it.toByte() }
        val body = part("").toByteArray(Charsets.ISO_8859_1).dropLast(2).toByteArray() + all + "\r\n--$boundary--\r\n".toByteArray()
        assertArrayEquals(all, Multipart.single(body, boundary, "file")!!.content)
    }

    @Test
    fun aSecondPartIsRefused() {
        assertNull(read(part("one") + part("two") + "--$boundary--\r\n"))
        assertNull(read(part("one") + part("two", "form-data; name=\"other\"") + "--$boundary--\r\n"))
        assertNull(read(part("one") + "--$boundary\r\n"))
    }

    @Test
    fun anyOtherShapeIsRefused() {
        val end = "--$boundary--\r\n"
        val not = listOf(
            "", end, "\r\n" + part("x") + end, "preamble\r\n" + part("x") + end, part("x"), part("x") + "--$boundary-",
            part("x") + end + "more", part("x") + "--$boundary--\r\n\r\n", part("x").replace(boundary, "another") + end,
            part("x", "form-data; name=\"other\"; filename=\"a.json\"") + end,
            part("x", "form-data; name=\"file\"") + end,
            part("x", "form-data; filename=\"a.json\"") + end,
            part("x", "form-data; name=\"file\"; filename=\"a.json\"; size=\"1\"") + end,
            part("x", "form-data; name=\"file\"; name=\"file\"; filename=\"a.json\"") + end,
            part("x", "form-data; name=\"file\"; filename*=\"utf-8''a.json\"") + end,
            part("x", "form-data; name=file; filename=a.json") + end,
            part("x", "attachment; name=\"file\"; filename=\"a.json\"") + end,
            part("x", "form-data; name=\"file\"; filename=\"a\\\"b.json\"") + end,
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.json\"\r\nX-More: 1\r\n\r\nx\r\n" + end,
            "--$boundary\r\nContent-Type: application/json\r\n\r\nx\r\n" + end,
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.json\"\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"b.json\"\r\n\r\nx\r\n" + end,
            "--$boundary\nContent-Disposition: form-data; name=\"file\"; filename=\"a.json\"\n\nx\n" + end,
            part("x", "form-data; name=\"file\"; filename=\"" + "n".repeat(3000) + "\"") + end,
        )
        for (body in not) assertNull(body.take(200), read(body))
    }

    @Test
    fun aNameIsCutDownToWhatANameNeeds() {
        assertEquals("stamp-apps-2026-09-29.json", Multipart.tidy("stamp-apps-2026-09-29.json"))
        assertEquals("apps (2).json", Multipart.tidy("C:\\Users\\someone\\Downloads\\apps (2).json"))
        assertEquals("passwd", Multipart.tidy("../../etc/passwd"))
        assertEquals("export.json", Multipart.tidy(""))
        assertEquals("export.json", Multipart.tidy("../.."))
        assertEquals("export.json", Multipart.tidy(" ... "))
        assertEquals("hidden", Multipart.tidy(".hidden"))
        assertEquals("ab.json", Multipart.tidy("a\u202eb\u0000<>:\"|?*\r\n.json"))
        assertEquals("\u6771\u4eac.json", Multipart.tidy("\u6771\u4eac.json"))
        assertEquals("n".repeat(80), Multipart.tidy("n".repeat(500)))
    }
}
