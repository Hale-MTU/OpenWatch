package tech.mmarca.openwatch.util

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

/** Stubs [Uri]'s percent codec as identity; no route argument here carries a reserved character. */
fun mockUriCodec() {
    mockkStatic(Uri::class)
    every { Uri.encode(any<String>()) } answers { firstArg<String>() }
    every { Uri.decode(any<String>()) } answers { firstArg<String>() }
}

fun unmockUriCodec() {
    unmockkStatic(Uri::class)
}
