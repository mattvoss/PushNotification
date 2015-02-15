package com.plugin.gcm;

import java.util.Random;
import java.text.DateFormat;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;

import com.google.android.gcm.GCMBaseIntentService;

@SuppressLint("NewApi")
public class GCMIntentService extends GCMBaseIntentService {

  private static final String TAG = "GCMIntentService";

  public GCMIntentService() {
    super("GCMIntentService");
  }

  @Override
  public void onRegistered(Context context, String regId) {

    Log.v(TAG, "onRegistered: " + regId);

    JSONObject json;

    try {
      json = new JSONObject().put("event", "registered");
      json.put("regid", regId);

      Log.v(TAG, "onRegistered: " + json.toString());
      // EV: added this because it makes a lot of sense
      PushPlugin.sendAsyncRegistrationResult(true, regId);

      // EV: kept this for backward compatibility
      // Send this JSON data to the JavaScript application above EVENT should be set to the msg type
      // In this case this is the registration ID
      PushPlugin.sendJavascript(json);

    } catch (JSONException e) {
      // No message to the user is sent, JSON failed
      Log.e(TAG, "onRegistered: JSON exception");
    }
  }

  @Override
  public void onUnregistered(Context context, String regId) {
    Log.d(TAG, "onUnregistered - regId: " + regId);
  }

  @Override
  protected void onMessage(Context context, Intent intent) {
    Log.d(TAG, "onMessage - context: " + context);
    boolean notify = true;
    double minutes = 0.0;

    String lastNotification = PreferenceManager.getDefaultSharedPreferences(
      getApplicationContext()).getString("lastNotification", "");
    int notificationInterval = PreferenceManager.getDefaultSharedPreferences(
      getApplicationContext()).getInt("notificationInterval", 30);
    Log.v(TAG, "Last Notification: " + lastNotification);
    Log.v(TAG, "Notification Interval: " + notificationInterval);

    if (lastNotification != null && !lastNotification.isEmpty()) {
      try {
        SimpleDateFormat dateParser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        Date last = dateParser.parse(lastNotification);
        SimpleDateFormat dateFormatUtc = new SimpleDateFormat("yyyy-MMM-dd HH:mm:ss");
        dateFormatUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat dateFormatLocal = new SimpleDateFormat("yyyy-MMM-dd HH:mm:ss");
        Date current = dateFormatLocal.parse( dateFormatUtc.format(new Date()) );
        long diffInMs = (current.getTime() - last.getTime());
        minutes = ((diffInMs / 1000) / 60);
        String s = String.valueOf(diffInMs);
        if (minutes < notificationInterval) {
          notify = false;
        }
        Log.v(TAG, "Differential: " + s);
      }
      catch (Exception e) {
        Log.e(TAG, "Exception " + e.getMessage());
      }
    }

    // Extract the payload from the message
    Bundle extras = intent.getExtras();
    if (extras != null) {
      // if we are in the foreground or background, just surface the payload, else post it to the statusbar
      if (PushPlugin.isInForeground()) {
        extras.putBoolean("foreground", true);
        PushPlugin.sendExtras(extras);
      } else {
        extras.putBoolean("foreground", false);
        if (PushPlugin.isActive()) {
          PushPlugin.sendExtras(extras);
        } else {
          // Send a notification if there is a message and app not active
          if (extras.getString("message") != null && extras.getString("message").length() != 0 && notify) {
            createNotification(context, extras);
          }
        }
      }
    }
  }

  public void createNotification(Context context, Bundle extras)
  {
    int notId = 0;

    try {
      notId = Integer.parseInt(extras.getString("notId", "0"));
    }
    catch(NumberFormatException e) {
      Log.e(TAG, "Number format exception - Error parsing Notification ID: " + e.getMessage());
    }
    catch(Exception e) {
      Log.e(TAG, "Number format exception - Error parsing Notification ID" + e.getMessage());
    }
    if (notId == 0) {
      // no notId passed, so assume we want to show all notifications, so make it a random number
      notId = new Random().nextInt(100000);
      Log.d(TAG, "Generated random notId: " + notId);
    } else {
      Log.d(TAG, "Received notId: " + notId);
    }


    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    String appName = getAppName(this);

    Intent notificationIntent = new Intent(this, PushHandlerActivity.class);
    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    notificationIntent.putExtra("pushBundle", extras);

    PendingIntent contentIntent = PendingIntent.getActivity(this, notId, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

    int defaults = Notification.DEFAULT_ALL;

    if (extras.getString("defaults") != null) {
      try {
        defaults = Integer.parseInt(extras.getString("defaults"));
      } catch (NumberFormatException ignored) {}
    }

    NotificationCompat.Builder mBuilder =
        new NotificationCompat.Builder(context)
            .setDefaults(defaults)
            .setSmallIcon(context.getApplicationInfo().icon)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(extras.getString("title"))
            .setTicker(extras.getString("title"))
            .setContentIntent(contentIntent)
            .setAutoCancel(true);

    String message = extras.getString("message");
    if (message != null) {
      mBuilder.setContentText(message);
    } else {
      mBuilder.setContentText("<missing message content>");
    }

    String msgcnt = extras.getString("msgcnt");
    if (msgcnt != null) {
      mBuilder.setNumber(Integer.parseInt(msgcnt));
    }

    String soundName = extras.getString("sound");
    if (soundName != null) {
      Resources r = getResources();
      int resourceId = r.getIdentifier(soundName, "raw", context.getPackageName());
      Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + resourceId);
      mBuilder.setSound(soundUri);
      defaults &= ~Notification.DEFAULT_SOUND;
      mBuilder.setDefaults(defaults);
    }

    mNotificationManager.notify(appName, notId, mBuilder.build());
  }

  private static String getAppName(Context context)
  {
    CharSequence appName =
        context
            .getPackageManager()
            .getApplicationLabel(context.getApplicationInfo());

    return (String)appName;
  }

  @Override
  public void onError(Context context, String error) {
    Log.e(TAG, "onError: " + error);
    PushPlugin.sendAsyncRegistrationResult(false, error);
  }

}
