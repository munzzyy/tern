package io.github.munzzyy.tern.core.qr

import java.io.File

/** Prints the code of every line of the file it is given, for tools/make-qr-fixtures.sh to draw and read back. */
fun main(args: Array<String>) {
    for (text in File(args[0]).readLines(Charsets.UTF_8)) {
        val code = QrEncoder.encode(text)
        println("version ${code.version} mask ${code.mask} size ${code.size}")
        for (y in 0 until code.size) println(String(CharArray(code.size) { x -> if (code.isDark(x, y)) '#' else '.' }))
    }
}
