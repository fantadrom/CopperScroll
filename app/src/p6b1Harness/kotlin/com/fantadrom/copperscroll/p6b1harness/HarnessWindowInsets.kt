package com.fantadrom.copperscroll.p6b1harness

import android.graphics.Rect
import android.view.View
import android.view.Window
import android.view.WindowInsets

/** Opt-in harness only. Padding belongs to the outer viewport, never its scrolling content. */
object HarnessWindowInsets {
    fun install(window: Window,outer: View) {
        val minimum=kotlin.math.ceil(40.0*outer.resources.displayMetrics.density).toInt()
        fun update() {
            val decor=window.decorView
            if(outer.width==0 || decor.height==0) return
            val insets=decor.rootWindowInsets ?: outer.rootWindowInsets ?: return
            val safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val rootAt=IntArray(2); val decorAt=IntArray(2)
            outer.getLocationOnScreen(rootAt); decor.getLocationOnScreen(decorAt)
            val left=(rootAt[0]-decorAt[0]).coerceAtLeast(0)
            val top=(rootAt[1]-decorAt[1]).coerceAtLeast(0)
            val consumed=HarnessInsetsGeometry.Edges(left,top,
                (decor.width-left-outer.width).coerceAtLeast(0),(decor.height-top-outer.height).coerceAtLeast(0))
            // adjustResize may already exclude the entire IME. Measure the remaining overlap,
            // rather than subtracting an IME inset from a viewport which has already shrunk.
            val visible=Rect(); decor.getWindowVisibleDisplayFrame(visible)
            val overlap=if(insets.isVisible(WindowInsets.Type.ime())) (rootAt[1]+outer.height-visible.bottom).coerceAtLeast(0) else 0
            val padding=HarnessInsetsGeometry.padding(minimum,HarnessInsetsGeometry.Edges(safe.left,safe.top,safe.right,safe.bottom),consumed,overlap)
            if(outer.paddingLeft!=padding.left || outer.paddingTop!=padding.top || outer.paddingRight!=padding.right || outer.paddingBottom!=padding.bottom)
                outer.setPadding(padding.left,padding.top,padding.right,padding.bottom)
        }
        outer.setOnApplyWindowInsetsListener { _,insets -> outer.post { update() }; insets }
        outer.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> update() }
        outer.viewTreeObserver.addOnGlobalLayoutListener { if(outer.isAttachedToWindow) update() }
        outer.requestApplyInsets()
    }
}
