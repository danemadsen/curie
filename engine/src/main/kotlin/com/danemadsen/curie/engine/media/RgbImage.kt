package com.danemadsen.curie.engine.media

/** An 8-bit RGB image: row-major, 3 bytes (R, G, B) per pixel, already upright (EXIF applied). */
class RgbImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "image must not be empty: ${width}x$height" }
        require(pixels.size == width * height * 3) {
            "${width}x$height RGB needs ${width * height * 3} bytes, got ${pixels.size}"
        }
    }
}
