package com.wmqc.miroot.car
import com.wmqc.miroot.display.MainDisplayUi

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.BitmapFactory
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R

/**
 * 背屏车控按钮配置弹窗：
 * 已选 / 可用双列表、长按已选项上移排序、点击已选删除、点击可用添加、
 * ListView 按内容测量高度、对话框宽度为屏宽 90%。
 */
object RearButtonConfigDialog {

    /** 顺序用于「可用功能」列表排序。 */
    val CONTROL_FUNCTIONS: List<String> = listOf(
        "锁车/解锁",
        "寻车",
        "点火/熄火",
        "空调",
        "开窗/关窗",
        "透气",
        "开后备箱",
        "座椅加热",
        "主驾加热",
        "副驾加热",
    )

    /** 根据功能名获取默认图标资源名（与背屏一致，取"关闭"状态图标） */
    private fun getIconResourceName(text: String): String = when (text) {
        "锁车/解锁" -> "ic_car_index_lock"
        "寻车" -> "ic_car_index_find_car"
        "点火/熄火" -> "ic_car_index_engine"
        "空调" -> "ic_ac_unit"
        "开窗/关窗" -> "ic_car_index_open_window"
        "透气" -> "ic_car_index_wind"
        "开后备箱" -> "ic_car_index_trunk"
        "座椅加热" -> "ic_seat_heating"
        "主驾加热" -> "ic_seat_heating_driver"
        "副驾加热" -> "ic_seat_heating_passenger"
        else -> "ic_car_index_find_car"
    }

    private val iconCache = mutableMapOf<String, android.graphics.Bitmap?>()

    /** 从 assets 加载按钮图标（带缓存） */
    private fun loadButtonIcon(context: android.content.Context, text: String): android.graphics.Bitmap? {
        return iconCache.getOrPut(text) {
            val name = getIconResourceName(text)
            val path = CarControlAssets.pngPath(name)
            CarControlAssets.decodeBitmap(context, path)
        }
    }

    fun show(
        activity: Activity,
        initialSelected: List<String>,
        onConfirmed: (List<String>) -> Unit,
    ) {
        val dm = activity.resources.displayMetrics
        val density = dm.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val selected = initialSelected.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (selected.isEmpty()) {
            selected.addAll(defaultRearButtonsForFirstInstall())
        }

        val available = CONTROL_FUNCTIONS.filter { it !in selected }.sorted().toMutableList()

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(20), dp(30), dp(20))
        }

        val selectedTitle = TextView(activity).apply {
            text = activity.getString(R.string.car_rear_btn_dialog_selected_title)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF2196F3.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        mainLayout.addView(selectedTitle)

        var selectedAdapter: BaseAdapter? = null
        var availableAdapter: BaseAdapter? = null
        var availableListView: ListView? = null

        selectedAdapter = object : BaseAdapter() {
            override fun getCount(): Int = selected.size
            override fun getItem(position: Int): Any = selected[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = if (convertView is LinearLayout) {
                    convertView
                } else {
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            TextView(activity).apply {
                                id = android.R.id.text1
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                                setTextColor(0xFF2196F3.toInt())
                                setTypeface(null, Typeface.BOLD)
                                setPadding(0, 0, dp(12), 0)
                                minimumWidth = (40 * density).toInt()
                            },
                        )
                        addView(
                            TextView(activity).apply {
                                id = android.R.id.text2
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                                setTextColor(0xFF333333.toInt())
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            },
                        )
                    }
                }
                val iconView = row.findViewById<ImageView>(android.R.id.icon)
                val indexText = row.findViewById<TextView>(android.R.id.text1)
                val nameText = row.findViewById<TextView>(android.R.id.text2)
                indexText.text = "${position + 1}."
                val funcName = selected[position]
                iconView.setImageBitmap(loadButtonIcon(activity, funcName))
                nameText.text = funcName
                row.setBackgroundColor(if (position % 2 == 0) 0xFFF5F5F5.toInt() else 0xFFFFFFFF.toInt())
                return row
            }
        }

        val selectedListView = ListView(activity).apply {
            adapter = selectedAdapter
            dividerHeight = dp(2)
            divider = ContextCompat.getDrawable(activity, android.R.drawable.divider_horizontal_bright)
        }
        mainLayout.addView(
            selectedListView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        updateListViewHeight(activity, selectedListView, selectedAdapter)

        selectedListView.setOnItemLongClickListener { _, _, position, _ ->
            if (position > 0) {
                val t = selected[position]
                selected[position] = selected[position - 1]
                selected[position - 1] = t
                selectedAdapter.notifyDataSetChanged()
                updateListViewHeight(activity, selectedListView, selectedAdapter)
                MainDisplayUi.showToast(activity, activity.getString(R.string.car_rear_btn_dialog_moved_up), Toast.LENGTH_SHORT)
            } else {
                MainDisplayUi.showToast(activity, activity.getString(R.string.car_rear_btn_dialog_already_top), Toast.LENGTH_SHORT)
            }
            true
        }

        selectedListView.setOnItemClickListener { _, _, position, _ ->
            if (selected.size > 1) {
                val removed = selected.removeAt(position)
                if (removed !in available) {
                    available.add(removed)
                    available.sort()
                }
                selectedAdapter.notifyDataSetChanged()
                updateListViewHeight(activity, selectedListView, selectedAdapter)
                availableAdapter?.notifyDataSetChanged()
                availableListView?.let { updateListViewHeight(activity, it, availableAdapter!!) }
            } else {
                MainDisplayUi.showToast(activity, activity.getString(R.string.car_rear_btn_dialog_keep_one), Toast.LENGTH_SHORT)
            }
        }

        val availableTitle = TextView(activity).apply {
            text = activity.getString(R.string.car_rear_btn_dialog_available_title)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF4CAF50.toInt())
            setPadding(0, dp(20), 0, dp(12))
        }
        mainLayout.addView(availableTitle)

        availableAdapter = object : BaseAdapter() {
            override fun getCount(): Int = available.size
            override fun getItem(position: Int): Any = available[position]
            override fun getItemId(position: Int): Long = position.toLong()

                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = if (convertView is LinearLayout) {
                    convertView
                } else {
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            ImageView(activity).apply {
                                id = android.R.id.icon
                                val iconSize = (24 * density).toInt()
                                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                                    rightMargin = dp(10)
                                }
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            },
                        )
                        addView(
                            TextView(activity).apply {
                                id = android.R.id.text1
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                                setTextColor(0xFF333333.toInt())
                            },
                        )
                    }
                }
                val iconView = row.findViewById<ImageView>(android.R.id.icon)
                val tv = row.findViewById<TextView>(android.R.id.text1)
                val funcName = available[position]
                iconView.setImageBitmap(loadButtonIcon(activity, funcName))
                tv.text = "+ ${funcName}"
                row.setBackgroundColor(if (position % 2 == 0) 0xFFF5F5F5.toInt() else 0xFFFFFFFF.toInt())
                return row
            }
        }

        val availLv = ListView(activity).apply {
            adapter = availableAdapter
            dividerHeight = dp(2)
            divider = ContextCompat.getDrawable(activity, android.R.drawable.divider_horizontal_bright)
        }
        availableListView = availLv
        mainLayout.addView(
            availLv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        updateListViewHeight(activity, availLv, availableAdapter)

        availLv.setOnItemClickListener { _, _, position, _ ->
            if (selected.size >= 8) {
                MainDisplayUi.showToast(activity, activity.getString(R.string.car_rear_btn_dialog_max_eight), Toast.LENGTH_SHORT)
                return@setOnItemClickListener
            }
            val func = available.removeAt(position)
            selected.add(func)
            selectedAdapter.notifyDataSetChanged()
            updateListViewHeight(activity, selectedListView, selectedAdapter)
            availableAdapter.notifyDataSetChanged()
            updateListViewHeight(activity, availLv, availableAdapter)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.car_rear_btn_dialog_title))
            .setView(mainLayout)
            .setPositiveButton(activity.getString(R.string.car_rear_btn_dialog_confirm), null)
            .setNegativeButton(activity.getString(R.string.car_rear_btn_dialog_cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) {
                    MainDisplayUi.showToast(activity, activity.getString(R.string.car_rear_btn_dialog_need_one), Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }
                onConfirmed(selected.toList())
                MainDisplayUi.showToast(activity, activity.getString(R.string.car_rear_btn_dialog_saved_toast), Toast.LENGTH_SHORT)
                dialog.dismiss()
            }
        }

        dialog.window?.setLayout((dm.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        dialog.window?.decorView?.post {
            updateListViewHeight(activity, selectedListView, selectedAdapter)
            updateListViewHeight(activity, availLv, availableAdapter)
        }
    }

    private fun updateListViewHeight(activity: Activity, listView: ListView, adapter: BaseAdapter) {
        listView.post {
            var totalHeight = 0
            val itemCount = adapter.count
            var listViewWidth = listView.width
            if (listViewWidth == 0) {
                val parent = listView.parent as? View
                listViewWidth = parent?.width ?: 0
                if (listViewWidth == 0) {
                    listViewWidth = activity.resources.displayMetrics.widthPixels
                }
            }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(listViewWidth, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            for (i in 0 until itemCount) {
                val itemView = adapter.getView(i, null, listView)
                itemView.measure(widthSpec, heightSpec)
                totalHeight += itemView.measuredHeight
            }
            if (itemCount > 0) {
                totalHeight += (itemCount - 1) * listView.dividerHeight
            }
            val lp = listView.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.height = totalHeight
                listView.layoutParams = lp
            }
        }
    }
}

/** 首次安装时背屏车控按钮的默认集合。 */
fun defaultRearButtonsForFirstInstall(): List<String> = listOf(
    "锁车/解锁",
    "寻车",
    "开后备箱",
    "开窗/关窗",
)
