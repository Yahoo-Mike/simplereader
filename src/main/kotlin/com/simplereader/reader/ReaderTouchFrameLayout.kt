package com.simplereader.reader

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

// we need to intercept the user longpressing for PDFs, so we can initiate a dictionary search.
// Unfortunately the PdfNavigator listener only exposes a single tap, not a longpress.
// So we have to intercept the user touching the screen and identify longpresses, while
// letting everything pass through to the PdfNavigatorFragment.  We do that by subclassing
// FrameLayout (ReaderTouchFrameLayout) which observes all touch events and exposes an
// onLongPress() callback that we can set if we want to capture user long pressing.
// The ReaderFragment is contained in this layout.  The PdfNavigator is hosted by the
// ReaderFragment, so that's how we observe the events before PdfNavigatorFragment consumes them.
// Note: *all* touch events (including long press) are passed on (dispatched) to super class.

class ReaderTouchFrameLayout  @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // you can set this callback to react to user long pressing.
    // note: this does not consume the long press and the event is still
    //       sent on to child views (including a readium NavigatorFragment's view)
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null

    ///////////////////////////////////////////////////////////////////////////
    // we use a gesture detector to "filter" the events and trigger our callback
    // when it detects a long press
    private val longpressListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true // arm long-press detection
        override fun onLongPress(e: MotionEvent) {          // long press detected
            onLongPress?.invoke(e.x, e.y)
        }
    }
    private val detector = GestureDetector(context, longpressListener )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // we observe all touch events, but rely on the detector to detect long presses
        detector.onTouchEvent(ev)

        // always forward to children (do not consume or intercept these touch events)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        onLongPress = null  // to be sure, to be sure
        super.onDetachedFromWindow()
    }
}
