package com.immichframe.immichframe

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlinx.coroutines.withContext

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    fun updateBackground(context: Context, appWidgetId: Int) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val backgroundType =
                prefs.getString("widgetBackground$appWidgetId", "square") ?: "square"

            val views = RemoteViews(context.packageName, R.layout.widget_view)
            val backgroundRes = when (backgroundType) {
                "square" -> R.drawable.widget_background_square
                "round" -> R.drawable.widget_background_circle
                "squircle" -> R.drawable.widget_background_squircle
                else -> R.drawable.widget_background_square
            }
            views.setInt(R.id.widget_frame, "setBackgroundResource", backgroundRes)

            /*//get the current size of the widget, set maxSize to twice that
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxSize = maxOf(width, height) * 2*/

            val intent = Intent(context, WidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(
                R.id.widgetImageView,
                pendingIntent
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)

            try {
                val savedUrl = prefs.getString("webview_url", "") ?: ""
                val authSecret = prefs.getString("authSecret", "") ?: ""

                if (savedUrl.isNotEmpty()) {
                    val retrofit = Helpers.createRetrofit(context, savedUrl, authSecret)
                    val apiService = retrofit.create(Helpers.ApiService::class.java)

                    CoroutineScope(Dispatchers.IO).launch {
                        getNextImage(apiService) { imageResponse ->
                            imageResponse?.let {
                                CoroutineScope(Dispatchers.IO).launch {
                                    // The try/catch around this launch does not protect it: a
                                    // CoroutineScope() with no handler reports a failure to the
                                    // thread's uncaught exception handler, which on Android
                                    // terminates the process. A single undecodable photo would
                                    // therefore kill the app while it was updating the widget.
                                    try {
                                        val decoded =
                                            Helpers.decodeBitmapFromBytes(it.randomImageBase64)
                                                ?: run {
                                                    Log.w(
                                                        "WidgetDebug",
                                                        "Widget photo could not be decoded, keeping the previous one"
                                                    )
                                                    return@launch
                                                }
                                        val randomBitmap =
                                            Helpers.reduceBitmapQuality(decoded, 1000)

                                        withContext(Dispatchers.Main) {
                                            views.setImageViewBitmap(
                                                R.id.widgetImageView,
                                                randomBitmap
                                            )
                                            runCatching {
                                                appWidgetManager.updateAppWidget(appWidgetId, views)
                                            }.onFailure { e ->
                                                // The widget was removed between the request and
                                                // the result (or the bitmap exceeds the host's
                                                // memory budget); nothing to update any more.
                                                Log.w("WidgetDebug", "Widget update dropped: ${e.message}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("WidgetDebug", "Error updating widget: ${e.message}")
                                    } catch (e: OutOfMemoryError) {
                                        Log.e("WidgetDebug", "Widget photo too large: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WidgetDebug", "Error updating widget: ${e.message}")
            }
        }

        private fun getNextImage(
            apiService: Helpers.ApiService,
            callback: (Helpers.ImageResponse?) -> Unit
        ) {
            apiService.getImageData().enqueue(object : Callback<Helpers.ImageResponse> {
                override fun onResponse(
                    call: Call<Helpers.ImageResponse>,
                    response: Response<Helpers.ImageResponse>
                ) {
                    if (response.isSuccessful) {
                        callback(response.body())
                    } else {
                        callback(null)
                    }
                }

                override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                    callback(null)
                }
            })
        }
    }
}
