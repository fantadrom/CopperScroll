package com.fantadrom.copperscroll.p6b1harness

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets

/** Opt-in harness only. Padding belongs to the outer viewport, never its scrolling content. */
object HarnessWindowInsets {
    private fun expected(window: Window,outer: View): HarnessInsetsGeometry.Edges? {
        val decor=window.decorView
        if(!outer.isAttachedToWindow || outer.width==0 || decor.height==0) return null
        val insets=decor.rootWindowInsets ?: outer.rootWindowInsets ?: return null
        val safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val rootAt=IntArray(2); val decorAt=IntArray(2)
        outer.getLocationOnScreen(rootAt); decor.getLocationOnScreen(decorAt)
        val left=(rootAt[0]-decorAt[0]).coerceAtLeast(0); val top=(rootAt[1]-decorAt[1]).coerceAtLeast(0)
        val consumed=HarnessInsetsGeometry.Edges(left,top,
            (decor.width-left-outer.width).coerceAtLeast(0),(decor.height-top-outer.height).coerceAtLeast(0))
        val visible=Rect(); decor.getWindowVisibleDisplayFrame(visible)
        val overlap=if(insets.isVisible(WindowInsets.Type.ime())) (rootAt[1]+outer.height-visible.bottom).coerceAtLeast(0) else 0
        return HarnessInsetsGeometry.padding(kotlin.math.ceil(40.0*outer.resources.displayMetrics.density).toInt(),
            HarnessInsetsGeometry.Edges(safe.left,safe.top,safe.right,safe.bottom),consumed,overlap)
    }
    fun install(window: Window,outer: View) {
        fun update() {
            val padding=expected(window,outer) ?: return
            if(outer.paddingLeft!=padding.left || outer.paddingTop!=padding.top || outer.paddingRight!=padding.right || outer.paddingBottom!=padding.bottom)
                outer.setPadding(padding.left,padding.top,padding.right,padding.bottom)
        }
        // Apply synchronously: a queued post could change padding after a test observed decor layout.
        // If the initial size/insets are not available yet, the layout/global-layout callbacks retry.
        outer.setOnApplyWindowInsetsListener { _,insets -> update(); insets }
        outer.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> update() }
        outer.viewTreeObserver.addOnGlobalLayoutListener { if(outer.isAttachedToWindow) update() }
        outer.requestApplyInsets()
    }
    /** Called on UI thread at pre-draw, including the children remeasured after setPadding. */
    fun settled(window: Window,outer: View): Boolean {
        val padding=expected(window,outer) ?: return false
        return !window.decorView.isLayoutRequested && treeLaidOut(outer) &&
            outer.paddingLeft==padding.left && outer.paddingTop==padding.top && outer.paddingRight==padding.right && outer.paddingBottom==padding.bottom
    }
    private fun treeLaidOut(view: View): Boolean {
        if(view.visibility==View.GONE) return true
        if(!view.isLaidOut || view.isLayoutRequested) return false
        return view !is ViewGroup || (0 until view.childCount).all { treeLaidOut(view.getChildAt(it)) }
    }
}
