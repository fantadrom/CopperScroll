package com.fantadrom.copperscroll.session.ui

import com.fantadrom.copperscroll.p6b1harness.HarnessInsetsGeometry as Geometry
import com.fantadrom.copperscroll.p6b1harness.HarnessInsetsGeometry.Edges
import org.junit.Assert.*
import org.junit.Test

class HarnessInsetsGeometryTest {
    @Test fun minimumAndCutoutUseMaximum() {
        assertEquals(Edges(0,40,0,40),Geometry.padding(40,Edges(),Edges()))
        assertEquals(Edges(0,64,0,48),Geometry.padding(40,Edges(0,64,0,48),Edges()))
    }
    @Test fun consumedInsetsAreNotDoubled() {
        assertEquals(Edges(0,16,0,0),Geometry.padding(40,Edges(0,24,0,48),Edges(0,24,0,48)))
        assertEquals(Edges(),Geometry.padding(40,Edges(0,64,0,48),Edges(0,64,0,48)))
    }
    @Test fun landscapeReservesBothSides() {
        assertEquals(Edges(70,40,42,40),Geometry.padding(40,Edges(70,0,42,0),Edges()))
        assertEquals(Edges(0,40,0,40),Geometry.padding(40,Edges(70,0,42,0),Edges(70,0,42,0)))
    }
    @Test fun imeCountsOnlyRemainingOverlap() {
        assertEquals(330,Geometry.padding(40,Edges(),Edges(),330).bottom)
        assertEquals(40,Geometry.padding(40,Edges(),Edges(),0).bottom) // already resized
        assertEquals(330,Geometry.padding(40,Edges(0,24,0,48),Edges(0,24,0,48),330).bottom)
    }
}
