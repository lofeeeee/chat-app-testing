package app.singular.client.ui

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowSnappingTest {

    @Test
    fun snapZonesCoverCornersEdgesAndCenter() {
        val area = workArea(null)

        // Corners (quadrant / square snapping)
        assertEquals(SnapZone.TOP_LEFT, snapZoneFor(Point(area.x + 2, area.y + 10)))
        assertEquals(SnapZone.TOP_LEFT, snapZoneFor(Point(area.x + 10, area.y + 2)))

        assertEquals(SnapZone.TOP_RIGHT, snapZoneFor(Point(area.x + area.width - 2, area.y + 10)))
        assertEquals(SnapZone.TOP_RIGHT, snapZoneFor(Point(area.x + area.width - 10, area.y + 2)))

        assertEquals(SnapZone.BOTTOM_LEFT, snapZoneFor(Point(area.x + 2, area.y + area.height - 10)))
        assertEquals(SnapZone.BOTTOM_LEFT, snapZoneFor(Point(area.x + 10, area.y + area.height - 2)))

        assertEquals(SnapZone.BOTTOM_RIGHT, snapZoneFor(Point(area.x + area.width - 2, area.y + area.height - 10)))
        assertEquals(SnapZone.BOTTOM_RIGHT, snapZoneFor(Point(area.x + area.width - 10, area.y + area.height - 2)))

        // Edges
        assertEquals(SnapZone.MAXIMISE, snapZoneFor(Point(area.x + area.width / 2, area.y + 2)))
        assertEquals(SnapZone.LEFT, snapZoneFor(Point(area.x + 2, area.y + area.height / 2)))
        assertEquals(SnapZone.RIGHT, snapZoneFor(Point(area.x + area.width - 2, area.y + area.height / 2)))

        // Center / Free floating
        assertEquals(SnapZone.NONE, snapZoneFor(Point(area.x + area.width / 2, area.y + area.height / 2)))
    }

    /**
     * The ghost must promise exactly what the snap delivers. Both read [snapTarget], so this
     * asserts the zones' target rectangles: dimensions against the work area, and that the
     * four quadrants tile it with no overlap and no gap.
     */
    @Test
    fun snapTargetsMatchTheirZones() {
        val area = workArea(null)
        val pointer = Point(area.x + area.width / 2, area.y + area.height / 2)

        val halfW = area.width / 2
        val halfH = area.height / 2

        // NONE previews nothing.
        assertEquals(null, snapTarget(SnapZone.NONE, pointer))

        // Maximise previews the whole work area.
        assertEquals(area, snapTarget(SnapZone.MAXIMISE, pointer))

        // Halves.
        assertEquals(
            Rectangle(area.x, area.y, halfW, area.height),
            snapTarget(SnapZone.LEFT, pointer),
        )
        assertEquals(
            Rectangle(area.x + halfW, area.y, halfW, area.height),
            snapTarget(SnapZone.RIGHT, pointer),
        )

        // Quadrants tile the work area exactly.
        val tl = snapTarget(SnapZone.TOP_LEFT, pointer)!!
        val tr = snapTarget(SnapZone.TOP_RIGHT, pointer)!!
        val bl = snapTarget(SnapZone.BOTTOM_LEFT, pointer)!!
        val br = snapTarget(SnapZone.BOTTOM_RIGHT, pointer)!!

        assertEquals(tl.width, tr.width)
        assertEquals(bl.width, br.width)
        assertEquals(tl.height, bl.height)
        assertEquals(tr.height, br.height)

        // No gaps: the union of the four quadrants is the work area.
        val union = tl.union(tr).union(bl).union(br)
        assertEquals(area.width, union.width)
        assertEquals(area.height, union.height)
        assertEquals(area.x, union.x)
        assertEquals(area.y, union.y)
    }
}
