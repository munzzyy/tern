package io.github.munzzyy.stamp.core.handoff

import java.security.MessageDigest

/**
 * For tools/check-handoff-page.sh. With "page" it prints the page the way a handoff serves it, head and all.
 * With "serve" it opens a handoff on the loopback address, prints where and with which code, and then
 * answers "take" on its input with what has arrived, until the input ends.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "page" -> {
            System.out.write(Pages.page(null, HandoffLimits()).bytes())
            System.out.flush()
        }
        "serve" -> serve()
        else -> {
            System.err.println("page or serve")
            System.exit(2)
        }
    }
}

private fun serve() {
    val server = HandoffServer.open(Phone.LOOPBACK)
    server.use {
        println("address ${server.address}")
        println("code ${server.code}")
        println("scan ${server.addressWithCode}")
        while (true) {
            val line = readlnOrNull() ?: return
            if (line != "take") continue
            for (item in server.take()) {
                when (item) {
                    is HandoffItem.Link -> println("link ${item.text}")
                    is HandoffItem.ExportFile -> println("file ${item.bytes.size} ${hex(MessageDigest.getInstance("SHA-256").digest(item.bytes))} ${item.name}")
                }
            }
            println("end ${server.end ?: "open"}")
        }
    }
}
