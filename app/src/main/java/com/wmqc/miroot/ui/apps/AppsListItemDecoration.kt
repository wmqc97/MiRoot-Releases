package com.wmqc.miroot.ui.apps

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class AppsListItemDecoration(
    private val spacingPx: Int,
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        val lastIndex = (parent.adapter?.itemCount ?: 0) - 1
        outRect.set(0, 0, 0, if (position == lastIndex) 0 else spacingPx)
    }
}

