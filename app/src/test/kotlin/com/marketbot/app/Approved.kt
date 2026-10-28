package com.marketbot.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs

/**
 * Screenshot regression check. Approved images live in app/src/test/screenshots;
 * a test compares its render to the approved one and fails on a visible change.
 * Re-approve after an intended change with: ./gradlew :app:testDebugUnitTest -PrecordScreens
 */
object Approved {
    private val dir = File("src/test/screenshots")
    private val record = System.getProperty("recordScreens") == "true"

    fun check(name: String, bmp: Bitmap) {
        val f = File(dir, "$name.png")
        if (record || !f.exists()) {
            dir.mkdirs(); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!record) throw AssertionError("No approved screenshot for $name; one was recorded at $f. Review and commit it.")
            return
        }
        val want = BitmapFactory.decodeFile(f.path)
        if (want.width != bmp.width || want.height != bmp.height) throw AssertionError("$name: size ${bmp.width}x${bmp.height}, approved ${want.width}x${want.height}")
        var differing = 0
        val n = bmp.width * bmp.height
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val a = bmp.getPixel(x, y); val b = want.getPixel(x, y)
            val d = maxOf(abs((a shr 16 and 0xff) - (b shr 16 and 0xff)), abs((a shr 8 and 0xff) - (b shr 8 and 0xff)), abs((a and 0xff) - (b and 0xff)))
            if (d > 40) differing++
        }
        // Anti-aliasing may move a few edge pixels between machines; a layout change moves thousands.
        if (differing > n / 200) {
            File("build/screens").mkdirs()
            File("build/screens/$name.actual.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            throw AssertionError("$name differs from the approved screenshot in $differing of $n pixels; see build/screens/$name.actual.png")
        }
    }
}
