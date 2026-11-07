package com.oakever.sudokuoverlay

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Detects the 10-by-10 grid lines from a normal light-theme Sudoku screenshot, then
 * maps ML Kit's recognized digit boxes into the 81 cells. */
object GridDetector {
    data class GridBounds(
        val left: Int,
        val top: Int,
        val cellWidth: Float,
        val cellHeight: Float
    ) {
        val right: Float get() = left + cellWidth * 9f
        val bottom: Float get() = top + cellHeight * 9f
    }

    private data class AxisFit(val origin: Int, val pitch: Int, val score: Float, val hits: Int)

    fun findGrid(bitmap: Bitmap): GridBounds? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 300 || height < 300) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val minDimension = min(width, height)
        val minPitch = max(18, (minDimension * 0.060f).toInt())
        val maxPitch = max(minPitch + 1, (minDimension * 0.155f).toInt())

        val horizontalScores = FloatArray(height)
        val xStart = (width * 0.025f).toInt()
        val xEnd = (width * 0.975f).toInt()
        val scanWidth = max(1, xEnd - xStart)
        for (y in 0 until height) {
            var dark = 0
            val rowOffset = y * width
            for (x in xStart until xEnd) {
                if (isDarkGridPixel(pixels[rowOffset + x])) dark++
            }
            horizontalScores[y] = dark.toFloat() / scanWidth
        }

        val horizontal = fitAxis(horizontalScores, minPitch, maxPitch) ?: return null
        if (horizontal.score < 0.18f || horizontal.hits < 7) return null
        val gridTop = horizontal.origin
        val gridBottom = gridTop + horizontal.pitch * 9
        if (gridBottom > height) return null

        val verticalScores = FloatArray(width)
        val scanHeight = max(1, gridBottom - gridTop)
        for (x in 0 until width) {
            var dark = 0
            for (y in gridTop until gridBottom) {
                if (isDarkGridPixel(pixels[y * width + x])) dark++
            }
            verticalScores[x] = dark.toFloat() / scanHeight
        }

        val pitchTolerance = max(3, (horizontal.pitch * 0.10f).toInt())
        val vertical = fitAxis(
            verticalScores,
            max(minPitch, horizontal.pitch - pitchTolerance),
            min(maxPitch, horizontal.pitch + pitchTolerance)
        ) ?: return null
        if (vertical.score < 0.18f || vertical.hits < 7) return null
        if (vertical.origin + vertical.pitch * 9 > width) return null

        return GridBounds(vertical.origin, horizontal.origin, vertical.pitch.toFloat(), horizontal.pitch.toFloat())
    }

    private fun isDarkGridPixel(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        val luminance = (red * 299 + green * 587 + blue * 114) / 1000
        return luminance < 218
    }

    private fun fitAxis(scores: FloatArray, minPitch: Int, maxPitch: Int): AxisFit? {
        var best: AxisFit? = null
        val lastPitch = min(maxPitch, scores.size / 9)
        for (pitch in minPitch..lastPitch) {
            val maxOrigin = scores.size - pitch * 9
            if (maxOrigin < 0) continue
            var origin = 0
            while (origin <= maxOrigin) {
                var sum = 0f
                var hits = 0
                for (line in 0..9) {
                    val expected = origin + line * pitch
                    var localMaximum = 0f
                    for (delta in -2..2) {
                        val at = expected + delta
                        if (at in scores.indices) localMaximum = max(localMaximum, scores[at])
                    }
                    sum += localMaximum
                    if (localMaximum >= 0.22f) hits++
                }
                val average = sum / 10f
                val adjusted = average + (hits / 10f) * 0.035f
                if (best == null || adjusted > best.score) {
                    best = AxisFit(origin, pitch, adjusted, hits)
                }
                origin += 2
            }
        }
        return best
    }

    fun mapDigits(recognizedText: Text, grid: GridBounds): IntArray {
        val cells = IntArray(81)
        val occupied = BooleanArray(81)
        for (block in recognizedText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val value = parseDigit(element.text) ?: continue
                    val rect: Rect = element.boundingBox ?: line.boundingBox ?: continue
                    val centerX = (rect.left + rect.right) / 2f
                    val centerY = (rect.top + rect.bottom) / 2f
                    if (centerX < grid.left || centerX >= grid.right ||
                        centerY < grid.top || centerY >= grid.bottom
                    ) continue
                    val col = floor((centerX - grid.left) / grid.cellWidth).toInt()
                    val row = floor((centerY - grid.top) / grid.cellHeight).toInt()
                    if (row !in 0..8 || col !in 0..8) continue
                    val index = row * 9 + col
                    // A Sudoku cell should contain one printed clue. Keep the first clean match.
                    if (!occupied[index]) {
                        cells[index] = value
                        occupied[index] = true
                    }
                }
            }
        }
        return cells
    }

    private fun parseDigit(text: String): Int? {
        val clean = text.trim()
        if (clean.length != 1) return null
        return clean[0].digitToIntOrNull()?.takeIf { it in 1..9 }
    }
}
