package io.github.munzzyy.stamp.core.json

import java.io.Reader

class JsonException(message: String, val offset: Long) : Exception("$message at offset $offset")

enum class JsonToken { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/**
 * Strict RFC 8259 pull parser. Streams, so a 40 MB repository index can be
 * walked with skipValue() without building a tree.
 */
class JsonReader(
    private val input: Reader,
    private val maxDepth: Int = 64,
    private val maxStringLength: Int = 16 * 1024 * 1024,
) {
    private enum class Scope { EMPTY_DOCUMENT, NONEMPTY_DOCUMENT, EMPTY_OBJECT, DANGLING_NAME, NONEMPTY_OBJECT, EMPTY_ARRAY, NONEMPTY_ARRAY }

    private val buffer = CharArray(8192)
    private var pos = 0
    private var limit = 0
    private var consumed = 0L
    private val stack = ArrayList<Scope>().apply { add(Scope.EMPTY_DOCUMENT) }
    private var peeked: JsonToken? = null
    private var peekedString: String? = null
    private var peekedBoolean = false

    fun peek(): JsonToken = peeked ?: advance().also { peeked = it }

    fun hasNext(): Boolean = peek().let { it != JsonToken.END_OBJECT && it != JsonToken.END_ARRAY && it != JsonToken.END_DOCUMENT }

    fun beginObject() = expect(JsonToken.BEGIN_OBJECT)

    fun endObject() = expect(JsonToken.END_OBJECT)

    fun beginArray() = expect(JsonToken.BEGIN_ARRAY)

    fun endArray() = expect(JsonToken.END_ARRAY)

    fun nextName(): String {
        expect(JsonToken.NAME)
        return peekedString!!
    }

    fun nextString(): String {
        expect(JsonToken.STRING)
        return peekedString!!
    }

    fun nextNumber(): JsonNumber {
        expect(JsonToken.NUMBER)
        return JsonNumber(peekedString!!)
    }

    fun nextBoolean(): Boolean {
        expect(JsonToken.BOOLEAN)
        return peekedBoolean
    }

    fun nextNull() = expect(JsonToken.NULL)

    fun skipValue() {
        var depth = 0
        do {
            when (peek()) {
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> depth++
                JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                JsonToken.END_DOCUMENT -> throw error("Unexpected end of document")
                else -> Unit
            }
            peeked = null
        } while (depth > 0)
    }

    fun readValue(): JsonValue = when (peek()) {
        JsonToken.BEGIN_OBJECT -> {
            beginObject()
            val fields = LinkedHashMap<String, JsonValue>()
            while (hasNext()) {
                val name = nextName()
                fields[name] = readValue()
            }
            endObject()
            JsonObject(fields)
        }
        JsonToken.BEGIN_ARRAY -> {
            beginArray()
            val items = ArrayList<JsonValue>()
            while (hasNext()) items.add(readValue())
            endArray()
            JsonArray(items)
        }
        JsonToken.STRING -> JsonString(nextString())
        JsonToken.NUMBER -> nextNumber()
        JsonToken.BOOLEAN -> JsonBool(nextBoolean())
        JsonToken.NULL -> {
            nextNull()
            JsonNull
        }
        else -> throw error("Expected a value but found ${peek()}")
    }

    fun requireEndOfDocument() {
        if (peek() != JsonToken.END_DOCUMENT) throw error("Trailing data after document")
    }

    private fun expect(token: JsonToken) {
        val actual = peek()
        if (actual != token) throw error("Expected $token but found $actual")
        peeked = null
    }

    private fun advance(): JsonToken {
        when (stack.last()) {
            Scope.EMPTY_DOCUMENT -> {
                stack[stack.lastIndex] = Scope.NONEMPTY_DOCUMENT
                return value(nextNonWhitespace() ?: throw error("Empty document"))
            }
            Scope.NONEMPTY_DOCUMENT -> {
                if (nextNonWhitespace() != null) throw error("Trailing data after document")
                return JsonToken.END_DOCUMENT
            }
            Scope.EMPTY_ARRAY -> {
                val c = nextNonWhitespace() ?: throw error("Unterminated array")
                if (c == ']') return close()
                stack[stack.lastIndex] = Scope.NONEMPTY_ARRAY
                return value(c)
            }
            Scope.NONEMPTY_ARRAY -> {
                return when (nextNonWhitespace() ?: throw error("Unterminated array")) {
                    ']' -> close()
                    ',' -> value(nextNonWhitespace() ?: throw error("Unterminated array"))
                    else -> throw error("Expected ',' or ']'")
                }
            }
            Scope.EMPTY_OBJECT -> {
                val c = nextNonWhitespace() ?: throw error("Unterminated object")
                if (c == '}') return close()
                return name(c)
            }
            Scope.NONEMPTY_OBJECT -> {
                return when (nextNonWhitespace() ?: throw error("Unterminated object")) {
                    '}' -> close()
                    ',' -> name(nextNonWhitespace() ?: throw error("Unterminated object"))
                    else -> throw error("Expected ',' or '}'")
                }
            }
            Scope.DANGLING_NAME -> {
                if (nextNonWhitespace() != ':') throw error("Expected ':'")
                stack[stack.lastIndex] = Scope.NONEMPTY_OBJECT
                return value(nextNonWhitespace() ?: throw error("Unterminated object"))
            }
        }
    }

    private fun close(): JsonToken {
        val closed = stack.removeAt(stack.lastIndex)
        return if (closed == Scope.EMPTY_ARRAY || closed == Scope.NONEMPTY_ARRAY) JsonToken.END_ARRAY else JsonToken.END_OBJECT
    }

    private fun name(first: Char): JsonToken {
        if (first != '"') throw error("Expected a quoted name")
        peekedString = readString()
        stack[stack.lastIndex] = Scope.DANGLING_NAME
        return JsonToken.NAME
    }

    private fun value(first: Char): JsonToken = when (first) {
        '{' -> push(Scope.EMPTY_OBJECT, JsonToken.BEGIN_OBJECT)
        '[' -> push(Scope.EMPTY_ARRAY, JsonToken.BEGIN_ARRAY)
        '"' -> {
            peekedString = readString()
            JsonToken.STRING
        }
        't' -> literal("rue", JsonToken.BOOLEAN).also { peekedBoolean = true }
        'f' -> literal("alse", JsonToken.BOOLEAN).also { peekedBoolean = false }
        'n' -> literal("ull", JsonToken.NULL)
        '-', in '0'..'9' -> {
            peekedString = readNumber(first)
            JsonToken.NUMBER
        }
        else -> throw error("Unexpected character '$first'")
    }

    private fun push(scope: Scope, token: JsonToken): JsonToken {
        if (stack.size > maxDepth) throw error("Nesting deeper than $maxDepth")
        stack.add(scope)
        return token
    }

    private fun literal(rest: String, token: JsonToken): JsonToken {
        for (expected in rest) {
            if (read() != expected.code) throw error("Invalid literal")
        }
        val next = peekChar()
        if (next != -1 && !isDelimiter(next.toChar())) throw error("Invalid literal")
        return token
    }

    private fun readNumber(first: Char): String {
        val sb = StringBuilder()
        sb.append(first)
        var c = first
        if (c == '-') {
            c = readDigit(sb) ?: throw error("Invalid number")
        }
        if (c != '0') takeDigits(sb)
        if (peekChar() == '.'.code) {
            sb.append(read().toChar())
            readDigit(sb) ?: throw error("Invalid number")
            takeDigits(sb)
        }
        val e = peekChar()
        if (e == 'e'.code || e == 'E'.code) {
            sb.append(read().toChar())
            val sign = peekChar()
            if (sign == '+'.code || sign == '-'.code) sb.append(read().toChar())
            readDigit(sb) ?: throw error("Invalid number")
            takeDigits(sb)
        }
        val next = peekChar()
        if (next != -1 && !isDelimiter(next.toChar())) throw error("Invalid number")
        if (sb.length > 400) throw error("Number too long")
        return sb.toString()
    }

    private fun readDigit(sb: StringBuilder): Char? {
        val c = peekChar()
        if (c !in '0'.code..'9'.code) return null
        read()
        sb.append(c.toChar())
        return c.toChar()
    }

    private fun takeDigits(sb: StringBuilder) {
        while (readDigit(sb) != null) {
            if (sb.length > 400) throw error("Number too long")
        }
    }

    private fun readString(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            when {
                c == -1 -> throw error("Unterminated string")
                c == '"'.code -> return sb.toString()
                c == '\\'.code -> sb.append(readEscape())
                c < 0x20 -> throw error("Control character in string")
                else -> sb.append(c.toChar())
            }
            if (sb.length > maxStringLength) throw error("String longer than $maxStringLength")
        }
    }

    private fun readEscape(): Char = when (val c = read()) {
        '"'.code -> '"'
        '\\'.code -> '\\'
        '/'.code -> '/'
        'b'.code -> '\b'
        'f'.code -> '\u000C'
        'n'.code -> '\n'
        'r'.code -> '\r'
        't'.code -> '\t'
        'u'.code -> {
            var result = 0
            repeat(4) {
                val digit = Character.digit(read().takeIf { it != -1 }?.toChar() ?: throw error("Unterminated escape"), 16)
                if (digit < 0) throw error("Invalid unicode escape")
                result = (result shl 4) or digit
            }
            result.toChar()
        }
        else -> throw error("Invalid escape" + if (c == -1) "" else " '\\${c.toChar()}'")
    }

    private fun isDelimiter(c: Char): Boolean = c == ',' || c == ']' || c == '}' || c == ' ' || c == '\n' || c == '\r' || c == '\t'

    private fun nextNonWhitespace(): Char? {
        while (true) {
            val c = read()
            if (c == -1) return null
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c.toChar()
        }
    }

    private fun peekChar(): Int {
        if (pos == limit && !fill()) return -1
        return buffer[pos].code
    }

    private fun read(): Int {
        if (pos == limit && !fill()) return -1
        consumed++
        return buffer[pos++].code
    }

    private fun fill(): Boolean {
        val n = input.read(buffer, 0, buffer.size)
        if (n <= 0) return false
        pos = 0
        limit = n
        return true
    }

    private fun error(message: String) = JsonException(message, consumed)
}
