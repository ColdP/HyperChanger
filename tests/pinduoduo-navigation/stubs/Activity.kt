package android.app

import java.io.ByteArrayInputStream

class Activity {
    val assets = Assets()
    fun createPackageContext(name: String, flags: Int) = this
}

class Assets {
    val opened = mutableListOf<String>()
    var missing = false
    fun open(name: String): ByteArrayInputStream {
        opened += name
        if (missing) error("Missing asset")
        return ByteArrayInputStream(byteArrayOf())
    }
}
