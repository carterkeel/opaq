package app.opaq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupingTest {
    // Pensacola and Gulf Breeze, about 8 km apart (the screenshots in #146).
    private val pensacola = 30.35 to -87.21
    private val gulfBreeze = 30.40 to -87.13

    @Test
    fun farApartAtCityZoomCloseTogetherZoomedOut() {
        assertEquals(listOf(listOf(0), listOf(1)), Grouping.group(listOf(pensacola, gulfBreeze), zoom = 10.0))
        assertEquals(listOf(listOf(0, 1)), Grouping.group(listOf(pensacola, gulfBreeze), zoom = 5.0))
    }

    @Test
    fun groupsKeepTheirOrderAndAnchor() {
        // Three in one house and one across town at street level.
        val house = 30.35 to -87.21
        val points = listOf(house, 30.35001 to -87.21001, 30.36 to -87.19, 30.35002 to -87.20999)
        assertEquals(listOf(listOf(0, 1, 3), listOf(2)), Grouping.group(points, zoom = 15.0))
    }

    @Test
    fun splitZoomSeparatesThem() {
        val zoom = assertNotNull(Grouping.splitZoom(listOf(pensacola, gulfBreeze)))
        assertEquals(2, Grouping.group(listOf(pensacola, gulfBreeze), zoom).size)
        assertTrue(zoom < 12)
    }

    @Test
    fun sameSpotCantBeSplit() {
        assertNull(Grouping.splitZoom(listOf(pensacola, pensacola)))
        // About a metre apart: would need a zoom past what the map shows.
        assertNull(Grouping.splitZoom(listOf(pensacola, 30.35001 to -87.21)))
    }
}
