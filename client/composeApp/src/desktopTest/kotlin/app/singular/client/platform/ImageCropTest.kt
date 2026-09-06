package app.singular.client.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crop maths, checked against actual pixels.
 *
 * A crop that is off by a quarter still produces a perfectly valid image, so nothing downstream
 * ever complains — it just silently uploads the wrong part of someone's photo. The only way to
 * catch that is to paint a source with known regions and assert which colour comes back.
 */
class ImageCropTest {

    /** A [w]x[h] image in quadrant colours: TL red, TR green, BL blue, BR white. */
    private fun quadrants(w: Int, h: Int): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val left = x < w / 2
                val top = y < h / 2
                image.setRGB(x, y, when {
                    left && top -> Color.RED.rgb
                    !left && top -> Color.GREEN.rgb
                    left -> Color.BLUE.rgb
                    else -> Color.WHITE.rgb
                })
            }
        }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    private fun decode(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes))!!

    private fun assertColour(expected: Color, actual: Int, where: String) {
        val c = Color(actual, true)
        assertEquals(expected.red, c.red, "$where red")
        assertEquals(expected.green, c.green, "$where green")
        assertEquals(expected.blue, c.blue, "$where blue")
    }

    @Test
    fun cropsTheRequestedCorner() {
        val src = quadrants(400, 400)

        // Top-left quarter: origin (0,0), half the short edge.
        val tl = decode(assertNotNull(cropSquare(src, 0f, 0f, 0.5f, outputPx = 64)))
        assertColour(Color.RED, tl.getRGB(tl.width / 2, tl.height / 2), "top-left crop")

        // Bottom-right quarter.
        val br = decode(assertNotNull(cropSquare(src, 0.5f, 0.5f, 0.5f, outputPx = 64)))
        assertColour(Color.WHITE, br.getRGB(br.width / 2, br.height / 2), "bottom-right crop")

        val tr = decode(assertNotNull(cropSquare(src, 0.5f, 0f, 0.5f, outputPx = 64)))
        assertColour(Color.GREEN, tr.getRGB(tr.width / 2, tr.height / 2), "top-right crop")
    }

    @Test
    fun outputIsSquareAtTheRequestedSize() {
        val out = decode(assertNotNull(cropSquare(quadrants(800, 600), 0f, 0f, 1f, outputPx = 128)))
        assertEquals(128, out.width)
        assertEquals(128, out.height)
    }

    @Test
    fun fullCropOfALandscapeSourceTakesTheShortEdge() {
        // 800x400: size 1f means "the whole short edge", i.e. a 400x400 square, not the full
        // 800 width — otherwise a square crop of a wide photo would not be square.
        val out = decode(assertNotNull(cropSquare(quadrants(800, 400), 0f, 0f, 1f, outputPx = 400)))
        assertEquals(out.width, out.height)
        assertEquals(400, out.width)
    }

    @Test
    fun neverUpscales() {
        // A 64px source asked for a 512px output should come back at 64, not blown up.
        val out = decode(assertNotNull(cropSquare(quadrants(64, 64), 0f, 0f, 1f, outputPx = 512)))
        assertEquals(64, out.width)
    }

    @Test
    fun outOfRangeSelectionsAreClampedRatherThanThrowing() {
        // The editor clamps too, but a crop running past the edge must never throw — it would
        // take the whole upload down for a rounding error.
        val src = quadrants(200, 200)
        assertNotNull(cropSquare(src, 0.9f, 0.9f, 0.5f, outputPx = 32), "past bottom-right")
        assertNotNull(cropSquare(src, -0.5f, -0.5f, 0.5f, outputPx = 32), "before top-left")
        assertNotNull(cropSquare(src, 0f, 0f, 5f, outputPx = 32), "size larger than the image")
    }

    @Test
    fun undecodableBytesReturnNullRatherThanThrowing() {
        // The caller falls back to uploading the original, so this has to be null and not an
        // exception — see ImageCropperDialog.
        assertNull(cropSquare("not an image".toByteArray(), 0f, 0f, 1f))
        assertNull(cropSquare(ByteArray(0), 0f, 0f, 1f))
    }

    @Test
    fun resultIsAValidPng() {
        val bytes = assertNotNull(cropSquare(quadrants(300, 300), 0.25f, 0.25f, 0.5f))
        assertTrue(bytes.size > 8, "empty output")
        // PNG magic number: an upload path that produced something undecodable would only fail
        // later, on someone else's screen.
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
    }
}
