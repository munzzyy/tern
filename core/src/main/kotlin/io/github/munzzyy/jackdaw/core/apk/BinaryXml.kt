package io.github.munzzyy.jackdaw.core.apk

internal class XmlAttribute(
    val namespace: String?,
    val name: String?,
    val resourceId: Int,
    val type: Int,
    val data: Int,
    val raw: String?,
    private val strings: StringPool,
) {
    val isReference: Boolean get() = type == TYPE_REFERENCE || type == TYPE_ATTRIBUTE || type == TYPE_DYNAMIC_REFERENCE

    fun string(): String? = when {
        type == TYPE_STRING -> strings[data]
        isReference -> null
        else -> raw
    }

    fun int(): Int? = when {
        type in TYPE_FIRST_INT..TYPE_LAST_INT -> data
        type == TYPE_STRING -> strings[data]?.trim()?.toIntOrNull()
        else -> null
    }

    fun boolean(): Boolean? = when {
        type == TYPE_BOOLEAN -> data != 0
        type == TYPE_STRING -> strings[data]?.trim()?.lowercase()?.toBooleanStrictOrNull()
        else -> null
    }

    companion object {
        const val TYPE_REFERENCE = 0x01
        const val TYPE_ATTRIBUTE = 0x02
        const val TYPE_STRING = 0x03
        const val TYPE_DYNAMIC_REFERENCE = 0x07
        const val TYPE_FIRST_INT = 0x10
        const val TYPE_BOOLEAN = 0x12
        const val TYPE_LAST_INT = 0x1f
    }
}

internal class XmlElement(val depth: Int, val name: String?, val attributes: List<XmlAttribute>)

/** Android's compiled XML format, read only as far as elements and their attributes. */
internal object BinaryXml {
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103
    private const val MAX_DEPTH = 256
    private const val MAX_ELEMENTS = 200_000

    fun elements(data: ByteArray): List<XmlElement> {
        if (data.size < 8 || data.u16(0) != CHUNK_XML) throw ApkFormatException("Not a compiled XML file")
        val headerSize = data.u16(2)
        val end = minOf(data.u32(4), data.size.toLong()).toInt()
        if (headerSize < 8 || headerSize > end) throw ApkFormatException("Bad XML header")
        var strings: StringPool? = null
        var resourceIds = IntArray(0)
        val out = ArrayList<XmlElement>()
        var depth = 0
        var at = headerSize
        while (at <= end - 8) {
            val type = data.u16(at)
            val chunkHeader = data.u16(at + 2)
            val chunkSize = data.u32(at + 4)
            if (chunkHeader < 8 || chunkSize < chunkHeader || chunkSize > end - at) throw ApkFormatException("Bad chunk at $at")
            val size = chunkSize.toInt()
            when (type) {
                CHUNK_STRING_POOL -> if (strings == null) strings = StringPool(data, at, chunkHeader, size)
                CHUNK_RESOURCE_MAP -> if (resourceIds.isEmpty()) resourceIds = IntArray((size - chunkHeader) / 4) { data.u32(at + chunkHeader + 4 * it).toInt() }
                CHUNK_START_ELEMENT -> {
                    val pool = strings ?: throw ApkFormatException("Element before the string pool")
                    if (++depth > MAX_DEPTH) throw ApkFormatException("XML nested too deeply")
                    if (out.size >= MAX_ELEMENTS) throw ApkFormatException("Too many XML elements")
                    out.add(element(data, at, chunkHeader, size, depth, pool, resourceIds))
                }
                CHUNK_END_ELEMENT -> if (--depth < 0) throw ApkFormatException("Unbalanced XML end element")
            }
            at += size
        }
        return out
    }

    private fun element(data: ByteArray, at: Int, headerSize: Int, size: Int, depth: Int, strings: StringPool, ids: IntArray): XmlElement {
        val ext = LeReader(data, at + headerSize, at + size)
        ext.skip(4)
        val name = strings[ext.i32()]
        val attributeStart = ext.u16()
        val attributeSize = ext.u16()
        val attributeCount = ext.u16()
        if (attributeCount > 0 && attributeSize < 20) throw ApkFormatException("Attribute records of $attributeSize bytes")
        val first = at + headerSize + attributeStart
        if (first.toLong() + attributeCount.toLong() * attributeSize > at + size) throw ApkFormatException("Attributes run past their element")
        val attributes = List(attributeCount) { i ->
            val a = LeReader(data, first + i * attributeSize, first + i * attributeSize + 20)
            val namespace = strings[a.i32()]
            val nameIndex = a.i32()
            val raw = strings[a.i32()]
            a.skip(3)
            val type = a.u8()
            val value = a.i32()
            val id = if (nameIndex in ids.indices) ids[nameIndex] else 0
            XmlAttribute(namespace, strings[nameIndex], id, type, value, raw, strings)
        }
        return XmlElement(depth, name, attributes)
    }
}

internal class StringPool(private val data: ByteArray, chunk: Int, headerSize: Int, size: Int) {
    private val count: Int
    private val utf8: Boolean
    private val offsets: Int
    private val stringsStart: Int
    private val end: Int = chunk + size
    private val cache: Array<String?>

    init {
        if (headerSize < 28) throw ApkFormatException("String pool header too small")
        val declared = data.u32(chunk + 8)
        val flags = data.u32(chunk + 16)
        val start = data.u32(chunk + 20)
        if (declared > (size - headerSize) / 4) throw ApkFormatException("String pool claims $declared strings")
        count = declared.toInt()
        utf8 = flags and 0x100L != 0L
        offsets = chunk + headerSize
        if (count > 0 && (start < headerSize || start > size)) throw ApkFormatException("String data outside its pool")
        stringsStart = chunk + start.toInt()
        cache = arrayOfNulls(count)
    }

    /** Like Android's own pool, an index that points nowhere (including -1, meaning none) reads as no string. */
    operator fun get(index: Int): String? {
        if (index < 0 || index >= count) return null
        cache[index]?.let { return it }
        val offset = data.u32(offsets + 4 * index)
        if (offset >= end - stringsStart) throw ApkFormatException("String $index outside its pool")
        val at = stringsStart + offset.toInt()
        return (if (utf8) utf8At(at) else utf16At(at)).also { cache[index] = it }
    }

    private fun utf8At(at: Int): String {
        val r = LeReader(data, at, end)
        utf8Length(r)
        val bytes = utf8Length(r)
        return String(r.bytes(bytes), Charsets.UTF_8)
    }

    private fun utf8Length(r: LeReader): Int {
        val first = r.u8()
        return if (first and 0x80 != 0) ((first and 0x7f) shl 8) or r.u8() else first
    }

    private fun utf16At(at: Int): String {
        val r = LeReader(data, at, end)
        val first = r.u16()
        val chars = if (first and 0x8000 != 0) ((first and 0x7fff) shl 16) or r.u16() else first
        if (chars > r.remaining / 2) throw ApkFormatException("String runs past its pool")
        return String(r.bytes(chars * 2), Charsets.UTF_16LE)
    }
}
