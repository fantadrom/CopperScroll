package com.fantadrom.copperscroll.session.android

import android.content.Context
import android.widget.FrameLayout

/** API31-compatible window visibility signal (not just Activity onStop). Activity owns this root;
 * clear the listener on detach. No model/worker retains the View. */
class SessionWindowRoot(context: Context): FrameLayout(context) {
    var windowSignal: (() -> Unit)?=null
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); windowSignal?.invoke() }
}
