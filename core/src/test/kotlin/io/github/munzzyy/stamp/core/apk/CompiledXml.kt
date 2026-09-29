package io.github.munzzyy.stamp.core.apk

import java.io.ByteArrayOutputStream

/** Writes Android's compiled XML format for tests, so unusual manifests can be built without aapt2. */
object CompiledXml {
    const val ANDROID = "http://schemas.android.com/apk/res/android"

    class Attr(val namespace: String?, val name: String, val id: Int, val type: Int, val data: Int = 0, val text: String? = null)

    class Node(val name: String, val attrs: List<Attr>, val children: List<Node> = emptyList())

    fun str(name: String, value: String, id: Int = 0, namespace: String? = ANDROID) = Attr(namespace, name, id, XmlAttribute.TYPE_STRING, text = value)

    fun int(name: String, value: Int, id: Int) = Attr(ANDROID, name, id, XmlAttribute.TYPE_FIRST_INT, value)

    fun bool(name: String, value: Boolean, id: Int) = Attr(ANDROID, name, id, XmlAttribute.TYPE_BOOLEAN, if (value) -1 else 0)

    fun ref(name: String, id: Int) = Attr(ANDROID, name, id, XmlAttribute.TYPE_REFERENCE, 0x7f0a0001)

    fun compile(root: Node, utf8: Boolean = false, resourceMap: Boolean = true): ByteArray {
        val pool = ArrayList<String>()
        val ids = ArrayList<Int>()
        fun walk(node: Node, visit: (Node) -> Unit) {
            visit(node)
            node.children.forEach { walk(it, visit) }
        }
        val slots = HashMap<Pair<String, Int>, Int>()
        walk(root) { n ->
            n.attrs.filter { it.id != 0 && resourceMap }.forEach {
                slots.getOrPut(it.name to it.id) { pool.size.also { _ -> pool.add(it.name); ids.add(it.id) } }
            }
        }
        fun index(s: String?): Int = if (s == null) -1 else (ids.size until pool.size).firstOrNull { pool[it] == s } ?: pool.size.also { pool.add(s) }
        fun nameIndex(a: Attr): Int = slots[a.name to a.id] ?: index(a.name)
        walk(root) { n ->
            index(n.name)
            n.attrs.forEach { index(it.namespace); nameIndex(it); index(it.text) }
        }

        val body = ByteArrayOutputStream()
        body.write(stringPool(pool, utf8))
        if (ids.isNotEmpty()) body.write(chunk(0x0180, 8, ids.fold(ByteArray(0)) { acc, id -> acc + le(id, 4) }))
        fun emit(node: Node) {
            var ext = le(-1, 4) + le(index(node.name), 4) + le(20, 2) + le(20, 2) + le(node.attrs.size, 2) + le(0, 6)
            for (a in node.attrs) {
                val data = if (a.type == XmlAttribute.TYPE_STRING) index(a.text) else a.data
                ext += le(index(a.namespace), 4) + le(nameIndex(a), 4) + le(if (a.text != null) index(a.text) else -1, 4) +
                    le(8, 2) + byteArrayOf(0, a.type.toByte()) + le(data, 4)
            }
            body.write(chunk(0x0102, 16, le(0, 4) + le(-1, 4) + ext))
            node.children.forEach { emit(it) }
            body.write(chunk(0x0103, 16, le(0, 4) + le(-1, 4) + le(-1, 4) + le(index(node.name), 4)))
        }
        emit(root)
        return chunk(0x0003, 8, body.toByteArray())
    }

    private fun stringPool(pool: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = ByteArrayOutputStream()
        for (s in pool) {
            offsets.write(le(data.size(), 4))
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                data.write(byteArrayOf(s.length.toByte(), bytes.size.toByte()))
                data.write(bytes)
                data.write(0)
            } else {
                data.write(le(s.length, 2))
                data.write(s.toByteArray(Charsets.UTF_16LE))
                data.write(le(0, 2))
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val header = le(pool.size, 4) + le(0, 4) + le(if (utf8) 0x100 else 0, 4) + le(28 + 4 * pool.size, 4) + le(0, 4)
        return chunk(0x0001, 28, header + offsets.toByteArray() + data.toByteArray())
    }

    private fun chunk(type: Int, headerSize: Int, rest: ByteArray): ByteArray =
        le(type, 2) + le(headerSize, 2) + le(8 + rest.size, 4) + rest

    private fun le(value: Int, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
}
