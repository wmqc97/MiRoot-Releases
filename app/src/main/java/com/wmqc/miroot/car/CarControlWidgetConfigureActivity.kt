package com.wmqc.miroot.car



import android.appwidget.AppWidgetManager

import android.content.Context

import android.content.Intent

import android.os.Bundle

import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

import com.google.android.material.button.MaterialButton

import com.google.android.material.checkbox.MaterialCheckBox

import com.google.android.material.slider.Slider

import com.wmqc.miroot.R



/**

 * 车控小组件配置：添加小组件时绑定实例，或从车控设置页菜单进入编辑全局默认。

 */

class CarControlWidgetConfigureActivity : AppCompatActivity() {



    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private var settingsMode = false



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        settingsMode = intent?.getBooleanExtra(EXTRA_SETTINGS_MODE, false) == true

        if (!settingsMode) {

            setResult(RESULT_CANCELED)

        }



        appWidgetId = intent?.extras?.getInt(

            AppWidgetManager.EXTRA_APPWIDGET_ID,

            AppWidgetManager.INVALID_APPWIDGET_ID,

        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (!settingsMode && appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {

            finish()

            return

        }



        setContentView(R.layout.activity_car_control_widget_configure)

        bindUi()

    }



    private fun bindUi() {

        val alphaSlider = findViewById<Slider>(R.id.widget_config_alpha_slider)
        val alphaValue = findViewById<TextView>(R.id.widget_config_alpha_value)
        val cornerSlider = findViewById<Slider>(R.id.widget_config_corner_slider)
        val cornerValue = findViewById<TextView>(R.id.widget_config_corner_value)

        val cbPlate = findViewById<MaterialCheckBox>(R.id.widget_config_show_plate)

        val cbTemp = findViewById<MaterialCheckBox>(R.id.widget_config_show_temp)

        val cbTime = findViewById<MaterialCheckBox>(R.id.widget_config_show_time)

        val cbOdometer = findViewById<MaterialCheckBox>(R.id.widget_config_show_odometer)

        val cbBattery = findViewById<MaterialCheckBox>(R.id.widget_config_show_battery)

        val cbTire = findViewById<MaterialCheckBox>(R.id.widget_config_show_tire)



        val savedAlpha = if (settingsMode) {

            CarControlWidgetPrefs.globalBgAlpha(this)

        } else {

            CarControlWidgetPrefs.bgAlpha(this, appWidgetId)

        }

        val savedFlags = if (settingsMode) {
            CarControlWidgetPrefs.globalDisplayFlags(this)
        } else {
            CarControlWidgetPrefs.displayFlags(this, appWidgetId)
        }
        val savedCorner = if (settingsMode) {
            CarControlWidgetPrefs.globalCornerRadiusDp(this)
        } else {
            CarControlWidgetPrefs.cornerRadiusDp(this, appWidgetId)
        }

        alphaSlider.value = savedAlpha.toFloat()
        updateAlphaLabel(alphaValue, savedAlpha)
        alphaSlider.addOnChangeListener { _, value, _ ->
            updateAlphaLabel(alphaValue, value.toInt())
        }

        cornerSlider.value = savedCorner.toFloat()
        updateCornerLabel(cornerValue, savedCorner)
        cornerSlider.addOnChangeListener { _, value, _ ->
            updateCornerLabel(cornerValue, value.toInt())
        }



        cbPlate.isChecked = CarControlWidgetPrefs.hasFlag(savedFlags, CarControlWidgetPrefs.FLAG_PLATE)

        cbTemp.isChecked = CarControlWidgetPrefs.hasTempDisplay(savedFlags)

        cbTime.isChecked = CarControlWidgetPrefs.hasFlag(savedFlags, CarControlWidgetPrefs.FLAG_UPDATE_TIME)

        cbOdometer.isChecked = CarControlWidgetPrefs.hasFlag(savedFlags, CarControlWidgetPrefs.FLAG_ODOMETER)

        cbBattery.isChecked = CarControlWidgetPrefs.hasFlag(savedFlags, CarControlWidgetPrefs.FLAG_BATTERY)

        cbTire.isChecked = CarControlWidgetPrefs.hasFlag(savedFlags, CarControlWidgetPrefs.FLAG_TIRE_PRESSURE)



        findViewById<MaterialButton>(R.id.widget_config_done).setOnClickListener {

            val flags = collectDisplayFlags(
                cbPlate.isChecked,
                cbTemp.isChecked,
                cbTime.isChecked,
                cbOdometer.isChecked,
                cbBattery.isChecked,
                cbTire.isChecked,
            )

            val alpha = alphaSlider.value.toInt()
            val cornerDp = cornerSlider.value.toInt()

            if (settingsMode) {
                CarControlWidgetPrefs.saveGlobal(this, alpha, flags, cornerDp)
                CarControlWidgetPrefs.applyGlobalToAllWidgets(this)
                finish()
            } else {
                CarControlWidgetPrefs.save(this, appWidgetId, alpha, flags, cornerDp)

                val manager = AppWidgetManager.getInstance(this)

                CarControlAppWidgetProvider.updateAll(this, manager, intArrayOf(appWidgetId))

                setResult(

                    RESULT_OK,

                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),

                )

                finish()

            }

        }

    }



    private fun collectDisplayFlags(
        plate: Boolean,
        temp: Boolean,
        time: Boolean,
        odometer: Boolean,
        battery: Boolean,
        tire: Boolean,
    ): Int {
        var flags = CarControlWidgetPrefs.MANDATORY_DISPLAY_FLAGS
        if (plate) flags = flags or CarControlWidgetPrefs.FLAG_PLATE
        if (temp) flags = flags or CarControlWidgetPrefs.FLAG_TEMP
        if (time) flags = flags or CarControlWidgetPrefs.FLAG_UPDATE_TIME
        if (odometer) flags = flags or CarControlWidgetPrefs.FLAG_ODOMETER
        if (battery) flags = flags or CarControlWidgetPrefs.FLAG_BATTERY
        if (tire) flags = flags or CarControlWidgetPrefs.FLAG_TIRE_PRESSURE
        return flags
    }



    private fun updateAlphaLabel(view: TextView, percent: Int) {
        view.text = getString(R.string.car_control_widget_bg_alpha_value, percent)
    }

    private fun updateCornerLabel(view: TextView, dp: Int) {
        view.text = getString(R.string.car_control_widget_corner_value, dp)
    }



    companion object {

        const val EXTRA_SETTINGS_MODE = "com.wmqc.miroot.car.EXTRA_WIDGET_SETTINGS_MODE"



        fun intentForSettings(context: Context): Intent =

            Intent(context, CarControlWidgetConfigureActivity::class.java)

                .putExtra(EXTRA_SETTINGS_MODE, true)

    }

}

