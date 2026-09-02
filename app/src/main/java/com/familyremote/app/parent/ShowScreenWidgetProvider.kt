package com.familyremote.app.parent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.familyremote.app.R

/**
 * Home-screen widget merging "open app" + "tap Show Screen" into a single tap (detailed-spec §4
 * target design). Its click `PendingIntent` launches [ShareStartTrampolineActivity] directly.
 *
 * NOTE: icon/label are an intentional placeholder. The widget's actual visual design was
 * explicitly deferred by the user (not yet decided) -- see docs/plans/screen-sharing-core/PLAN.md
 * and the task that introduced this file. Do not over-invest in custom art here; swap the layout
 * once a real design decision is made.
 */
class ShowScreenWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_show_screen)
            val launchIntent = Intent(context, ShareStartTrampolineActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetShowScreenButton, pendingIntent)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
