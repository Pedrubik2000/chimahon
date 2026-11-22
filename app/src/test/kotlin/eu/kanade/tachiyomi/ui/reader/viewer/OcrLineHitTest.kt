package eu.kanade.tachiyomi.ui.reader.viewer

import chimahon.ocr.OcrCharacterLine
import chimahon.ocr.OcrHitTester
import chimahon.ocr.OcrNormalizedBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OcrLineHitTest {

    // Two vertical columns, read right (offset 0..3) then left (offset 4..7).
    private val columns = listOf(
        OcrCharacterLine(Unit, "ABCD", 0, left = 60f, top = 0f, right = 70f, bottom = 100f, rotation = 0f, vertical = true),
        OcrCharacterLine(Unit, "EFGH", 4, left = 40f, top = 0f, right = 50f, bottom = 100f, rotation = 0f, vertical = true),
    )

    @Test
    fun `tap between columns snaps to the nearest column and character`() {
        // x=57 is 3 from the right column, 7 from the left; y=60 is the third cell (C).
        val hit = OcrHitTester.hitLines(columns, 57f, 60f, snapDistance = Float.POSITIVE_INFINITY)
        assertEquals(2, hit?.textOffset)
        // Without snapping (the novel viewer's page-wide hit test) a gap still gives nothing.
        assertNull(OcrHitTester.hitLines(columns, 57f, 60f))
    }

    @Test
    fun `tap far from any line or block gives nothing`() {
        assertNull(OcrHitTester.hitLines(columns, 200f, 60f, snapDistance = 5f))
        val block = OcrNormalizedBounds(0.4f, 0f, 0.7f, 1f)
        assertNull(OcrHitTester.findBlock(listOf(block), 0.95f, 0.5f) { it })
    }

    @Test
    fun `vertical lines of one column read top to bottom before the next column`() {
        // A and B are the top and bottom halves of the right column; B sits a hair further right.
        val block = OcrTextBlock(
            xmin = 0.55f, ymin = 0.1f, xmax = 0.86f, ymax = 0.6f,
            lines = listOf("A", "B", "C"),
            vertical = true,
            lineGeometries = listOf(
                OcrLineGeometry(0.75f, 0.10f, 0.85f, 0.30f),
                OcrLineGeometry(0.76f, 0.35f, 0.86f, 0.60f),
                OcrLineGeometry(0.55f, 0.10f, 0.65f, 0.50f),
            ),
        )
        assertEquals("ABC", block.orderedFullText)
        assertEquals(1, block.toOrderedOffset(1))
    }
}
