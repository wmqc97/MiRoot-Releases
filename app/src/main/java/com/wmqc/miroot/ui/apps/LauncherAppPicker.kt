package com.wmqc.miroot.ui.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wmqc.miroot.R

/**
 * 带应用图标与实时搜索的启动器应用单选对话框（与 [AppsFragment.queryLauncherApps] 数据源一致）。
 */
object LauncherAppPicker {

    fun show(
        activity: AppCompatActivity,
        rows: List<InstalledAppRow>,
        title: CharSequence? = null,
        onPicked: (InstalledAppRow) -> Unit,
    ) {
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(activity)
        val root = inflater.inflate(R.layout.dialog_pick_launcher_app, null, false)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler_pick_launcher_apps)
        val search = root.findViewById<TextInputEditText>(R.id.edit_search_pick_app)
        val empty = root.findViewById<TextView>(R.id.text_pick_launcher_empty)

        val full = rows.toList()
        var filtered: List<InstalledAppRow> = full

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(title ?: activity.getString(R.string.rear_gesture_pick_app))
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        val adapter =
            object : RecyclerView.Adapter<RowVh>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVh {
                    val row = inflater.inflate(R.layout.item_pick_launcher_app, parent, false)
                    return RowVh(row)
                }

                override fun onBindViewHolder(holder: RowVh, position: Int) {
                    val row = filtered[position]
                    holder.bind(row) {
                        onPicked(row)
                        dialog.dismiss()
                    }
                }

                override fun getItemCount(): Int = filtered.size
            }

        fun applyFilter(query: String) {
            val t = query.trim().lowercase()
            filtered =
                if (t.isEmpty()) {
                    full
                } else {
                    full.filter { row ->
                        row.label.lowercase().contains(t) || row.packageName.lowercase().contains(t)
                    }
                }
            adapter.notifyDataSetChanged()
            val none = filtered.isEmpty()
            empty.visibility = if (none) View.VISIBLE else View.GONE
            recycler.visibility = if (none) View.GONE else View.VISIBLE
        }

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        search.doOnTextChanged { text, _, _, _ ->
            applyFilter(text?.toString().orEmpty())
        }

        dialog.show()
    }

    private class RowVh(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.image_app_icon)
        private val label: TextView = itemView.findViewById(R.id.text_app_label)
        private val pkg: TextView = itemView.findViewById(R.id.text_app_package)

        fun bind(row: InstalledAppRow, onClick: () -> Unit) {
            icon.setImageDrawable(row.icon)
            icon.contentDescription = row.label
            label.text = row.label
            pkg.text = row.packageName
            itemView.setOnClickListener { onClick() }
        }
    }
}
