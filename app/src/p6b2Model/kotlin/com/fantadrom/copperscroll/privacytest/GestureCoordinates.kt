package com.fantadrom.copperscroll.privacytest

/** Pure coordinate transform used by the instrumentation; no View or text escapes into diagnostics. */
object GestureCoordinates {
    fun rootToScreen(rootX: Int,rootY: Int,screenX: Int,screenY: Int,scrollX: Int,scrollY: Int,globalX: Int,globalY: Int)=Point(
        (rootX+screenX-scrollX-globalX).toFloat(),(rootY+screenY-scrollY-globalY).toFloat())
    data class Point(val x: Float,val y: Float)
    fun screen(contentX: Float,contentY: Float,paddingLeft: Int,paddingTop: Int,
        scrollX: Int,scrollY: Int,screenX: Int,screenY: Int)=Point(
        screenX+paddingLeft+contentX-scrollX,screenY+paddingTop+contentY-scrollY)
}
