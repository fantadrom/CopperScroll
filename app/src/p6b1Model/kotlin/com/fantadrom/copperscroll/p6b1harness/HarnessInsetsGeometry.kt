package com.fantadrom.copperscroll.p6b1harness

/** Distances are pixels relative to the window. Already consumed decor insets are not added twice. */
object HarnessInsetsGeometry {
    data class Edges(val left: Int=0,val top: Int=0,val right: Int=0,val bottom: Int=0)
    fun padding(minimum: Int,safe: Edges,consumed: Edges,imeOverlap: Int=0)=Edges(
        (safe.left-consumed.left).coerceAtLeast(0),
        (maxOf(minimum,safe.top)-consumed.top).coerceAtLeast(0),
        (safe.right-consumed.right).coerceAtLeast(0),
        maxOf((maxOf(minimum,safe.bottom)-consumed.bottom).coerceAtLeast(0),imeOverlap.coerceAtLeast(0)))
}
