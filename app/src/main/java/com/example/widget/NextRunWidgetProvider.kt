package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import kotlin.math.roundToInt

class NextRunWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        private const val PREFS_NAME = "runcoach_widget_prefs"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_REF_TEMP = "ref_temp"
        private const val KEY_OUTFIT = "outfit"
        private const val KEY_ALERTS = "alerts"

        fun saveAndPushWidgetUpdate(
            context: Context,
            locationName: String,
            terrainLabel: String,
            distanceKm: Double,
            referenceTempC: Double,
            outfitSummary: String,
            alertsSummary: String
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_SUBTITLE, "$locationName • $terrainLabel ${distanceKm.roundToInt()} km")
                .putString(KEY_REF_TEMP, "Réf ${referenceTempC.roundToInt()}°C")
                .putString(KEY_OUTFIT, "Tenue : $outfitSummary")
                .putString(KEY_ALERTS, "Alertes : $alertsSummary")
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, NextRunWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(componentName)
            for (id in ids) {
                updateAppWidget(context, manager, id)
            }
        }

        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val subtitle = prefs.getString(KEY_SUBTITLE, "Ouvre l'appli pour préparer ta sortie")
            val refTemp = prefs.getString(KEY_REF_TEMP, "Réf —")
            val outfit = prefs.getString(KEY_OUTFIT, "Tenue : —")
            val alerts = prefs.getString(KEY_ALERTS, "Alertes : —")

            val views = RemoteViews(context.packageName, R.layout.widget_next_run)
            views.setTextViewText(R.id.widget_subtitle, subtitle)
            views.setTextViewText(R.id.widget_ref_temp, refTemp)
            views.setTextViewText(R.id.widget_outfit_summary, outfit)
            views.setTextViewText(R.id.widget_alerts, alerts)

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
