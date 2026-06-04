package com.wmqc.miroot.car

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.LogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车控桌面小组件：圆角、左信息右车模、紧凑按钮、二次确认遮罩。
 */
class CarControlAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            CarControlWidgetPrefs.remove(context, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateAll(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_CONFIGURATION_CHANGED -> refreshAll(context)
            CarControlWidgetActionReceiver.ACTION_REFRESH -> refreshAll(context)
        }
    }

    companion object {
        private const val TAG = "CarControlWidget"
        private const val DEFAULT_WIDTH_DP = 250
        private const val DEFAULT_HEIGHT_DP = 130
        private const val REQUEST_REFRESH_BASE = 8_000

        fun refreshAll(context: Context) {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx)
            val ids = manager.getAppWidgetIds(
                ComponentName(appCtx, CarControlAppWidgetProvider::class.java),
            )
            if (ids.isNotEmpty()) updateAll(appCtx, manager, ids)
        }

        fun updateWidgets(context: Context, appWidgetIds: IntArray) {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx)
            updateAll(appCtx, manager, appWidgetIds)
        }

        fun updateAll(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            val appCtx = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                var vehicleUi: CarVehicleDisplayUi? = null
                var vehicleStatus: VehicleStatusService.VehicleStatusInfo? = null
                try {
                    val loaded = CarVehicleDisplayHelper.loadWithStatus(appCtx)
                    vehicleUi = loaded.first
                    vehicleStatus = loaded.second
                    CarControlVehiclePrefsSync.applyFromStatus(appCtx, vehicleStatus)
                    VehiclePositionHelper.fromStatus(vehicleStatus)?.let { coords ->
                        VehiclePositionHelper.saveCache(
                            appCtx,
                            coords,
                            VehiclePositionHelper.isMarsGcj(vehicleStatus.marsCoordinates),
                        )
                    }
                } catch (_: Exception) {
                    vehicleUi = CarVehicleDisplayHelper.loadCached(appCtx)
                    vehicleStatus = CarVehicleDisplayCache.loadStatus(appCtx)
                }
                val prefs = CarControlVehiclePrefsSync.carPrefs(appCtx)
                val buttons = CarRearButtonsConfig.firstFour(appCtx)
                LogHelper.d(TAG, "widget button slots: $buttons")
                val locationText = VehiclePositionHelper.resolveLocationTextForWidget(appCtx, vehicleStatus)
                val carDecodeSide = CarControlWidgetSupport.maxCarModelDecodeSidePx(
                    context = appCtx,
                    appWidgetIds = appWidgetIds,
                    optionsForId = { widgetId ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                            manager.getAppWidgetOptions(widgetId)
                        } else {
                            null
                        }
                    },
                    defaultWidthDp = DEFAULT_WIDTH_DP,
                    defaultHeightDp = DEFAULT_HEIGHT_DP,
                )
                val carModel = CarModelImageLoader.loadForWidget(appCtx, carDecodeSide)
                carModel?.let {
                    LogHelper.d(
                        TAG,
                        "carModel decode ${it.width}x${it.height} targetSide=$carDecodeSide bytes=${it.allocationByteCount}",
                    )
                }

                for (widgetId in appWidgetIds) {
                    val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        manager.getAppWidgetOptions(widgetId)
                    } else {
                        null
                    }
                    val (widthDp, heightDp) = CarControlWidgetSupport.readWidgetSizeDp(
                        options,
                        DEFAULT_WIDTH_DP,
                        DEFAULT_HEIGHT_DP,
                    )
                    val cornerDp = CarControlWidgetPrefs.cornerRadiusDp(appCtx, widgetId).toFloat()
                    val metrics = CarControlWidgetSupport.layoutMetrics(
                        appCtx,
                        widthDp,
                        heightDp,
                        cornerDp,
                    )
                    val views = try {
                        buildRemoteViews(
                            appCtx,
                            widgetId,
                            vehicleUi,
                            vehicleStatus,
                            prefs,
                            buttons,
                            carModel,
                            locationText,
                            metrics,
                        )
                    } catch (e: Exception) {
                        LogHelper.w(TAG, "buildRemoteViews failed id=$widgetId: ${e.message}")
                        buildMinimalRemoteViews(appCtx, widgetId, vehicleUi, locationText) to 0
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            manager.updateAppWidget(widgetId, views.first)
                            LogHelper.d(
                                TAG,
                                "updateAppWidget id=$widgetId bitmapBytes=${views.second}",
                            )
                        } catch (e: Exception) {
                            LogHelper.w(TAG, "updateAppWidget failed id=$widgetId: ${e.message}")
                            try {
                                manager.updateAppWidget(
                                    widgetId,
                                    buildMinimalRemoteViews(appCtx, widgetId, vehicleUi, locationText),
                                )
                            } catch (fallback: Exception) {
                                LogHelper.w(TAG, "fallback update failed id=$widgetId: ${fallback.message}")
                            }
                        }
                    }
                }
            }
        }

        private fun buildMinimalRemoteViews(
            context: Context,
            appWidgetId: Int,
            vehicleUi: CarVehicleDisplayUi?,
            locationText: String,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_car_control)
            val theme = CarControlWidgetSupport.themeForWidget(context)
            val dash = context.getString(R.string.car_control_vehicle_dash)
            val alpha = CarControlWidgetPrefs.bgAlpha(context, appWidgetId)
            val cornerDp = CarControlWidgetPrefs.cornerRadiusDp(context, appWidgetId).toFloat()
            val manager = AppWidgetManager.getInstance(context)
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                manager.getAppWidgetOptions(appWidgetId)
            } else {
                null
            }
            val (widthDp, heightDp) = CarControlWidgetSupport.readWidgetSizeDp(
                options,
                DEFAULT_WIDTH_DP,
                DEFAULT_HEIGHT_DP,
            )
            val metrics = CarControlWidgetSupport.layoutMetrics(context, widthDp, heightDp, cornerDp)
            CarControlWidgetSupport.applyWidgetBackground(
                views,
                theme,
                alpha,
                metrics.cornerRadiusPx,
                metrics.widthPx,
                metrics.heightPx,
            )
            views.setTextViewText(R.id.widget_range_value, vehicleUi?.rangeKmText ?: dash)
            views.setTextColor(R.id.widget_range_value, theme.textPrimary)
            views.setViewVisibility(R.id.widget_car_model, View.GONE)
            views.setTextViewText(R.id.widget_vehicle_location, locationText)
            views.setTextColor(R.id.widget_vehicle_location, theme.textPrimary)
            views.setViewVisibility(R.id.widget_confirm_overlay, View.GONE)
            val refreshIntent = actionIntent(
                context,
                appWidgetId,
                CarControlWidgetActionReceiver.ACTION_REFRESH,
                appWidgetId + REQUEST_REFRESH_BASE,
                null,
                null,
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshIntent)
            return views
        }

        private fun buildRemoteViews(
            context: Context,
            appWidgetId: Int,
            vehicleUi: CarVehicleDisplayUi?,
            vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
            prefs: android.content.SharedPreferences,
            buttons: List<String>,
            carModel: android.graphics.Bitmap?,
            locationText: String,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ): Pair<RemoteViews, Int> {
            val views = RemoteViews(context.packageName, R.layout.widget_car_control)
            val theme = CarControlWidgetSupport.themeForWidget(context)
            val alpha = CarControlWidgetPrefs.bgAlpha(context, appWidgetId)
            val flags = CarControlWidgetPrefs.displayFlags(context, appWidgetId)

            // RemoteViews 经 Binder 的位图数量有限，必须先绑定四个按钮图标。
            bindWidgetButtons(
                context,
                views,
                appWidgetId,
                prefs,
                buttons,
                vehicleStatus,
                theme,
                metrics,
            )

            val refreshIntent = actionIntent(
                context,
                appWidgetId,
                CarControlWidgetActionReceiver.ACTION_REFRESH,
                appWidgetId + REQUEST_REFRESH_BASE,
                null,
                null,
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshIntent)
            CarControlWidgetSupport.bindWidgetBitmap(
                views,
                R.id.widget_refresh_btn,
                CarControlWidgetSupport.loadRefreshIcon(context, theme.btnIconIdle, metrics.iconPx),
            )

            bindLeftInfoPanel(context, views, vehicleUi, vehicleStatus, theme, metrics)
            bindOptionalInfoPanel(context, views, vehicleUi, vehicleStatus, flags, theme, metrics)
            bindPlateAboveCar(context, views, vehicleUi, flags, theme, metrics)
            bindCarTemperature(context, views, vehicleUi, flags, theme, metrics)

            views.setTextViewText(R.id.widget_vehicle_location, locationText)
            views.setTextColor(R.id.widget_vehicle_location, theme.textPrimary)
            CarControlWidgetSupport.applyTextSizeSp(
                views,
                R.id.widget_vehicle_location,
                metrics.locationTextSp,
            )
            views.setViewVisibility(R.id.widget_vehicle_location, View.VISIBLE)

            val openSettings = PendingIntent.getActivity(
                context,
                appWidgetId + 10_000,
                Intent(context, CarControlSettingsActivity::class.java),
                pendingFlags(),
            )
            views.setOnClickPendingIntent(R.id.widget_car_model, openSettings)

            bindMetaBar(context, views, vehicleUi, flags, theme, metrics)

            if (carModel != null) {
                val scaled = CarControlWidgetSupport.scaleCarModelForWidget(
                    carModel,
                    metrics.carMaxWidthPx,
                    metrics.carMaxHeightPx,
                )
                CarControlWidgetSupport.bindCarModelBitmap(views, scaled)
                LogHelper.d(
                    TAG,
                    "carModel id=$appWidgetId ${scaled.width}x${scaled.height} bytes=${scaled.allocationByteCount}",
                )
                views.setViewVisibility(R.id.widget_car_model, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_car_model, View.GONE)
            }

            CarControlWidgetSupport.applyWidgetBackground(
                views,
                theme,
                alpha,
                metrics.cornerRadiusPx,
                metrics.widthPx,
                metrics.heightPx,
            )

            bindConfirmOverlay(context, views, appWidgetId, metrics)

            return views to 0
        }

        private fun bindWidgetButtons(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            prefs: android.content.SharedPreferences,
            buttons: List<String>,
            vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val btnIconIds = intArrayOf(
                R.id.widget_btn0_icon,
                R.id.widget_btn1_icon,
                R.id.widget_btn2_icon,
                R.id.widget_btn3_icon,
            )
            val btnRootIds = intArrayOf(
                R.id.widget_btn0,
                R.id.widget_btn1,
                R.id.widget_btn2,
                R.id.widget_btn3,
            )
            for (i in 0 until 4) {
                val functionKey = buttons.getOrNull(i)
                if (functionKey.isNullOrBlank()) {
                    views.setViewVisibility(btnRootIds[i], View.INVISIBLE)
                    continue
                }
                views.setViewVisibility(btnRootIds[i], View.VISIBLE)
                val remoteOn = CarControlWidgetState.remoteOn(functionKey, prefs, vehicleStatus)
                val display = CarControlWidgetState.displayText(functionKey, remoteOn)
                val active = CarControlWidgetState.isButtonActive(functionKey, remoteOn)
                val iconTint = if (active) theme.btnIconActive else theme.btnIconIdle
                val iconBitmap = CarControlWidgetSupport.loadWidgetButtonIcon(
                    context,
                    display,
                    vehicleStatus,
                    iconTint,
                    metrics.iconPx,
                )
                val bound = iconBitmap != null
                if (bound) {
                    CarControlWidgetSupport.bindWidgetBitmap(views, btnIconIds[i], iconBitmap)
                }
                LogHelper.d(
                    TAG,
                    "btn$i id=$appWidgetId key=$functionKey display=$display active=$active " +
                        "remoteOn=$remoteOn tint=${Integer.toHexString(iconTint)} " +
                        "bound=$bound bytes=${iconBitmap?.allocationByteCount ?: 0}",
                )
                val click = actionIntent(
                    context,
                    appWidgetId,
                    CarControlWidgetActionReceiver.ACTION_BUTTON,
                    appWidgetId * 10 + i,
                    functionKey,
                    display,
                )
                views.setOnClickPendingIntent(btnRootIds[i], click)
            }
        }

        private fun bindConfirmOverlay(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val pending = CarControlWidgetPrefs.getPending(context, appWidgetId)
            if (pending == null) {
                views.setViewVisibility(R.id.widget_confirm_overlay, View.GONE)
                return
            }
            views.setViewVisibility(R.id.widget_confirm_overlay, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_confirm_message,
                context.getString(R.string.car_control_widget_confirm_fmt, pending.displayText),
            )
            views.setOnClickPendingIntent(
                R.id.widget_confirm_cancel,
                actionIntent(
                    context,
                    appWidgetId,
                    CarControlWidgetActionReceiver.ACTION_CANCEL,
                    appWidgetId + 5000,
                    null,
                    null,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_confirm_ok,
                actionIntent(
                    context,
                    appWidgetId,
                    CarControlWidgetActionReceiver.ACTION_CONFIRM,
                    appWidgetId + 6000,
                    null,
                    null,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_confirm_scrim,
                actionIntent(
                    context,
                    appWidgetId,
                    CarControlWidgetActionReceiver.ACTION_CANCEL,
                    appWidgetId + 7000,
                    null,
                    null,
                ),
            )
        }

        private fun actionIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
            requestCode: Int,
            functionKey: String?,
            displayText: String?,
        ): PendingIntent {
            val intent = Intent(context, CarControlWidgetActionReceiver::class.java).apply {
                setPackage(context.packageName)
                this.action = action
                putExtra(CarControlWidgetActionReceiver.EXTRA_APP_WIDGET_ID, appWidgetId)
                if (!functionKey.isNullOrBlank()) {
                    putExtra(CarControlWidgetActionReceiver.EXTRA_FUNCTION_KEY, functionKey)
                }
                if (!displayText.isNullOrBlank()) {
                    putExtra(CarControlWidgetActionReceiver.EXTRA_DISPLAY_TEXT, displayText)
                }
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                pendingFlags(),
            )
        }

        private fun bindLeftInfoPanel(
            context: Context,
            views: RemoteViews,
            vehicleUi: CarVehicleDisplayUi?,
            vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val dash = context.getString(R.string.car_control_vehicle_dash)
            val rangeText = vehicleUi?.rangeKmText ?: dash
            val (rangeNum, rangeUnit) = CarControlWidgetSupport.parseRangeWidgetParts(rangeText, dash)
            val fuelPct = vehicleUi?.fuelPercent ?: -1
            val fuelText = if (fuelPct >= 0) "${fuelPct}%" else dash

            views.setTextViewText(R.id.widget_range_value, rangeNum)
            views.setTextColor(R.id.widget_range_value, theme.textPrimary)
            CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_range_value, metrics.rangeMainSp)

            val hasRange = rangeUnit.isNotEmpty()
            if (hasRange) {
                views.setViewVisibility(R.id.widget_range_unit, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_range_unit,
                    context.getString(R.string.car_control_widget_range_unit),
                )
                views.setTextColor(R.id.widget_range_unit, theme.textSecondary)
                CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_range_unit, metrics.rangeLabelSp)
            } else {
                views.setViewVisibility(R.id.widget_range_unit, View.GONE)
            }

            views.setViewVisibility(R.id.widget_range_label, View.VISIBLE)
            views.setTextViewText(R.id.widget_range_label, context.getString(R.string.car_control_widget_range_label))
            views.setTextColor(R.id.widget_range_label, theme.textSecondary)
            CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_range_label, metrics.rangeLabelSp)

            CarControlWidgetSupport.bindWidgetBitmap(
                views,
                R.id.widget_fuel_icon,
                CarControlWidgetSupport.loadFuelIcon(context, metrics.fuelIconPx),
            )
            views.setTextViewText(R.id.widget_fuel_percent, fuelText)
            views.setTextColor(R.id.widget_fuel_percent, theme.fuelAccent)
            CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_fuel_percent, metrics.fuelPercentSp)

            val progress = fuelPct.coerceIn(0, 100)
            views.setProgressBar(R.id.widget_fuel_progress, 100, progress, false)
            views.setViewVisibility(
                R.id.widget_fuel_progress,
                if (fuelPct >= 0) View.VISIBLE else View.INVISIBLE,
            )

        }

        private fun bindMetaBar(
            context: Context,
            views: RemoteViews,
            vehicleUi: CarVehicleDisplayUi?,
            flags: Int,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val dash = context.getString(R.string.car_control_vehicle_dash)
            val showTime = CarControlWidgetPrefs.hasFlag(flags, CarControlWidgetPrefs.FLAG_UPDATE_TIME)
            if (showTime) {
                val timeRaw = vehicleUi?.updateTimeShort ?: dash
                views.setViewVisibility(R.id.widget_time_value, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_time_value,
                    if (timeRaw == dash) dash else context.getString(R.string.car_control_widget_update_fmt, timeRaw),
                )
                views.setTextColor(R.id.widget_time_value, theme.textSecondary)
                CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_time_value, metrics.timeTextSp)
            } else {
                views.setViewVisibility(R.id.widget_time_value, View.GONE)
            }
        }


        private fun bindOptionalInfoPanel(
            context: Context,
            views: RemoteViews,
            vehicleUi: CarVehicleDisplayUi?,
            vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
            flags: Int,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val dash = context.getString(R.string.car_control_vehicle_dash)
            var anyVisible = false

            fun bindLine(viewId: Int, enabled: Boolean, line: String?, fallback: String? = null) {
                val text = line?.takeIf { it.isNotBlank() } ?: fallback
                if (enabled && text != null) {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setTextViewText(viewId, text)
                    views.setTextColor(viewId, theme.textSecondary)
                    CarControlWidgetSupport.applyTextSizeSp(views, viewId, metrics.infoTextSp)
                    anyVisible = true
                } else {
                    views.setViewVisibility(viewId, View.GONE)
                }
            }

            bindLine(
                R.id.widget_odometer_value,
                CarControlWidgetPrefs.hasFlag(flags, CarControlWidgetPrefs.FLAG_ODOMETER),
                CarControlWidgetDisplayLines.odometerLine(context, vehicleStatus),
            )
            bindLine(
                R.id.widget_battery_value,
                CarControlWidgetPrefs.hasFlag(flags, CarControlWidgetPrefs.FLAG_BATTERY),
                CarControlWidgetDisplayLines.batteryLine(context, vehicleStatus),
            )
            bindTireGrid(context, views, vehicleStatus, flags, theme, metrics) { shown ->
                if (shown) anyVisible = true
            }

            views.setViewVisibility(
                R.id.widget_optional_info,
                if (anyVisible) View.VISIBLE else View.GONE,
            )
        }

        private fun bindPlateAboveCar(
            context: Context,
            views: RemoteViews,
            vehicleUi: CarVehicleDisplayUi?,
            flags: Int,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val dash = context.getString(R.string.car_control_vehicle_dash)
            if (!CarControlWidgetPrefs.hasFlag(flags, CarControlWidgetPrefs.FLAG_PLATE)) {
                views.setViewVisibility(R.id.widget_plate_value, View.GONE)
                return
            }
            val plate = vehicleUi?.plateNo ?: dash
            views.setViewVisibility(R.id.widget_plate_value, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_plate_value,
                context.getString(R.string.car_control_widget_line_plate, plate),
            )
            views.setTextColor(R.id.widget_plate_value, theme.textSecondary)
            CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_plate_value, metrics.infoTextSp)
        }

        private fun bindTireGrid(
            context: Context,
            views: RemoteViews,
            vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
            flags: Int,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
            onShown: (Boolean) -> Unit,
        ) {
            val dash = context.getString(R.string.car_control_vehicle_dash)
            val tireIds = intArrayOf(
                R.id.widget_tire_lf,
                R.id.widget_tire_rf,
                R.id.widget_tire_lr,
                R.id.widget_tire_rr,
            )
            if (!CarControlWidgetPrefs.hasFlag(flags, CarControlWidgetPrefs.FLAG_TIRE_PRESSURE)) {
                views.setViewVisibility(R.id.widget_tire_block, View.GONE)
                onShown(false)
                return
            }
            val cells = CarControlWidgetDisplayLines.tirePressureCells(context, vehicleStatus, dash)
            if (cells == null) {
                views.setViewVisibility(R.id.widget_tire_block, View.GONE)
                onShown(false)
                return
            }
            views.setViewVisibility(R.id.widget_tire_block, View.VISIBLE)
            val texts = arrayOf(cells.lf, cells.rf, cells.lr, cells.rr)
            for (i in tireIds.indices) {
                views.setTextViewText(tireIds[i], texts[i])
                views.setTextColor(tireIds[i], theme.textSecondary)
                CarControlWidgetSupport.applyTextSizeSp(views, tireIds[i], metrics.infoTextSp)
            }
            onShown(true)
        }

        private fun bindCarTemperature(
            context: Context,
            views: RemoteViews,
            vehicleUi: CarVehicleDisplayUi?,
            flags: Int,
            theme: CarControlWidgetSupport.WidgetTheme,
            metrics: CarControlWidgetSupport.WidgetLayoutMetrics,
        ) {
            val dash = context.getString(R.string.car_control_vehicle_dash)
            if (!CarControlWidgetPrefs.hasTempDisplay(flags)) {
                views.setViewVisibility(R.id.widget_temp_value, View.GONE)
                return
            }
            views.setViewVisibility(R.id.widget_temp_value, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_temp_value,
                CarControlWidgetDisplayLines.temperatureLine(context, vehicleUi, dash),
            )
            views.setTextColor(R.id.widget_temp_value, theme.textSecondary)
            CarControlWidgetSupport.applyTextSizeSp(views, R.id.widget_temp_value, metrics.locationTextSp)
        }

        private fun pendingFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
