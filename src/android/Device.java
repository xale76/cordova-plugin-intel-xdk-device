/*
Copyright 2015 Intel Corporation

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file 
except in compliance with the License. You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the 
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific language governing permissions 
and limitations under the License
*/

package com.intel.xdk.device;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.TelephonyManager;
import android.text.ClipboardManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsoluteLayout;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class provides access to various features on the device.
 */
public class Device extends CordovaPlugin {

    private Activity activity;
    private CordovaWebView webView;

    private final static String phonegap = "3.2.0";
    private final static String platform = "Android";
    private boolean wasLoadingStopped = true;
    private HttpURLConnection connection;

    private WakeLock wl;
    private BroadcastReceiver batteryChangeReceiver;
    private BroadcastReceiver screenStatusReceiver;

    private String strStoreUrl;

    private boolean shouldAutoRotate = true;
    private String rotateOrientation = "";

    //Used in show remote site
    private AbsoluteLayout remoteLayout;
    ImageButton remoteClose;
    private WebView remoteView;
    private boolean isShowingRemoteSite;
    static int remoteCloseXPort = 0, remoteCloseYPort = 0, remoteCloseXLand = 0, remoteCloseYLand = 0, remoteCloseH = 0, remoteCloseW = 0;

    private int displayOrientation;
    //Used in intel.xdk.orientation.change
    private String lastOrientation;

    private Method evaluateJavascript, sendJavascript;
    private ValueCallback emptyVC;


    /**
     * Constructor.
     */
    public Device() {
    }

    @Override
    public void initialize(CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);

        this.activity = cordova.getActivity();
        this.webView = webView;

        //remote site support
        remoteLayout = new AbsoluteLayout(activity);
        remoteLayout.setBackgroundColor(Color.BLACK);
        //hide the remote site display until needed
        remoteLayout.setVisibility(View.GONE);
        //create the close button
        remoteClose = new ImageButton(activity);
        remoteClose.setBackgroundColor(Color.TRANSPARENT);
        Drawable remoteCloseImage = null;
        remoteCloseImage = activity.getResources().getDrawable(activity.getResources().getIdentifier("remote_close", "drawable", activity.getPackageName()));

        File remoteCloseImageFile = new File(activity.getFilesDir(), "_intelxdk/remote_close.png");
        if (remoteCloseImageFile.exists()) {
            remoteCloseImage = (Drawable.createFromPath(remoteCloseImageFile.getAbsolutePath()));
        } else {
            remoteCloseImage = (activity.getResources().getDrawable(activity.getResources().getIdentifier("remote_close", "drawable", activity.getPackageName())));
        }

        //set the button image
        //remoteClose.setImageDrawable(remoteCloseImage);
        remoteClose.setBackgroundDrawable(remoteCloseImage);
        //set up the button click action
        remoteClose.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                closeRemoteSite();
            }
        });
        //add the close button
        remoteLayout.addView(remoteClose);

        final ViewGroup parent = (ViewGroup) webView.getEngine().getView().getParent();
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (parent != null) {
                    //add layout to activity root layout
                    parent.addView(remoteLayout, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER));

                }
            }
        });

        //Initialize the orientation.
        lastOrientation = "unknown";

        //Listen to the orientation change.
        OrientationEventListener listener = new OrientationEventListener(activity) {

            @Override
            public void onOrientationChanged(int orientation) {
                //Log.d("orientation","orientation: " + orientation);

                String currentOrientation = "unknown";
                boolean orientationChanged = false;
                //int displayOrientation = 0;

                if (orientation > 345 || orientation < 15) {
                    currentOrientation = "portrait";
                    displayOrientation = 0;
                } else if (orientation > 75 && orientation < 105) {
                    currentOrientation = "landscape";
                    displayOrientation = 90;
                } else if (orientation > 165 && orientation < 195) {
                    currentOrientation = "portrait";
                    displayOrientation = 180;
                } else if (orientation > 255 && orientation < 285) {
                    currentOrientation = "landscape";
                    displayOrientation = -90;
                }

                if (currentOrientation.equals("unknown")) {
                    currentOrientation = lastOrientation;
                }

                if (!currentOrientation.equals(lastOrientation)) {
                    orientationChanged = true;
                    Log.d("orientation", "Orientation changes from " + lastOrientation + " to " + currentOrientation + ", current orientation: " + orientation + ".");
                }

                if (orientationChanged) {
                    String js = "javascript:try{intel.xdk.device.orientation='" + displayOrientation + "';}catch(e){}var e = document.createEvent('Events');e.initEvent('intel.xdk.device.orientation.change', true, true);e.success=true;e.orientation='" + displayOrientation + "';document.dispatchEvent(e);";
                    injectJS(js);
                }

                lastOrientation = currentOrientation;
            }

        };

        listener.enable();
        registerScreenStatusReceiver();

        //cache references to methods for use in injectJS
        try {
            evaluateJavascript = webView.getClass().getMethod("evaluateJavascript", String.class, ValueCallback.class);
        } catch (Exception e) {
        }

        try {
            sendJavascript = webView.getClass().getMethod("sendJavascript", String.class);
        } catch (Exception e) {
        }

        emptyVC =
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String s) {
                    }
                };


    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          The action to execute.
     * @param args            JSONArray of arguments for the plugin.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return True when the action was valid, false otherwise.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("openWebPage")) {
            this.openWebPage(args.getString(0));
        } else if (action.equals("getRemoteData")) {
            //String success = args.getString(3);
            //String error = args.getString(4);
            if (args.length() <= 3) {
                getRemoteData(args.getString(0), args.getString(1), args.getString(2), callbackContext);
            } else {
                getRemoteData(args.getString(0), args.getString(1), args.getString(2), args.getString(3), args.getString(4));
            }

        } else if (action.equals("getRemoteDataExt")) {
            final JSONObject json = args.getJSONObject(0);
            cordova.getThreadPool().execute(new Runnable() {

                @Override
                public void run() {
                    Device.this.getRemoteDataExt(json);
                }

            });
        } else if (action.equals("getRemoteDataWithID")) {
            String success = args.getString(4);
            String error = args.getString(5);
            if (success == "null" && error == "null") {
                this.getRemoteDataWithID(args.getString(0), args.getString(1), args.getString(2), args.getInt(3), callbackContext);
            } else {
                this.getRemoteDataWithID(args.getString(0), args.getString(1), args.getString(2), args.getInt(3), args.getString(4), args.getString(5));
            }
        } else if (action.equals("hideStatusBar")) {
            this.hideStatusBar();
        } else if (action.equals("launchExternal")) {
            this.launchExternal(args.getString(0));
        } else if (action.equals("managePower")) {
            this.managePower(args.getBoolean(0), args.getBoolean(1));
        } else if (action.equals("runInstallNativeApp")) {
            this.runInstallNativeApp(args.getString(0), args.getString(1), args.getString(2), args.getString(3));
        } else if (action.equals("sendEmail")) {
            this.sendEmail(args.getString(0), args.getString(1), args.getString(2), args.getBoolean(3), args.getString(4), args.getString(5));
        } else if (action.equals("sendSMS")) {
            this.sendSMS(args.getString(0), args.getString(1));
        } else if (action.equals("setAutoRotate")) {
            this.setAutoRotate(args.getBoolean(0));
        } else if (action.equals("setRotateOrientation")) {
            this.setRotateOrientation(args.getString(0));
        } else if (action.equals("setBasicAuthentication")) {
            this.setBasicAuthentication(args.getString(0), args.getString(1), args.getString(2));
        } else if (action.equals("showRemoteSite")) {
            this.showRemoteSite(args.getString(0), args.getInt(1), args.getInt(2), args.getInt(3), args.getInt(4));
        } else if (action.equals("showRemoteSiteExt")) {
            this.showRemoteSite(args.getString(0), args.getInt(1), args.getInt(2), args.getInt(3), args.getInt(4), args.getInt(5), args.getInt(6));
        } else if (action.equals("closeRemoteSite")) {
            this.closeRemoteSite();
        } else if (action.equals("updateConnection")) {
            this.updateConnection();
        } else if (action.equals("mainViewExecute")) {
            this.mainViewExecute(args.getString(0));
        } else if (action.equals("copyToClipboard")) {
            this.copyToClipboard(args.getString(0));
        } else if (action.equals("initialize")) {
            this.initialize();
        } else {
            return false;
        }

        // All actions are async.
        //callbackContext.success();
        return true;
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------


    public void getRemoteData(String requestUrl, String requestMethod, String requestBody, String successCallback, String errorCallback) {
        Log.d("getRemoteData", "url: " + requestUrl + ", method: " + requestMethod + ", body: " + requestBody);

        try {
            URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod(requestMethod);

            //Write requestBody
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(requestBody);
            outputStream.flush();
            outputStream.close();

            //Get response code and response message
            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            //Get response Message
            DataInputStream inputStream = new DataInputStream(connection.getInputStream());
            if (responseCode == 200) {
                String temp;
                String responseBody = "";
                while ((temp = inputStream.readLine()) != null) {
                    responseBody += temp;
                }
                //callbackContext.success(responseBody);
                String js = "javascript:" + successCallback + "('" + responseBody + "');";
                injectJS(js);
            } else {
                //callbackContext.error("Fail to get the response, response code: " + responseCode + ", response message: " + responseMessage);
                String js = "javascript:" + errorCallback + "(" + "'response code :" + responseCode + "');";
                injectJS(js);
            }

            inputStream.close();
        } catch (IOException e) {
            Log.d("request", e.getMessage());
        }
    }

    public void getRemoteData(String requestUrl, String requestMethod, String requestBody, CallbackContext callbackContext) {
        try {
            URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod(requestMethod);

            //Write requestBody
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(requestBody);
            outputStream.flush();
            outputStream.close();

            //Get response code and response message
            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            //Get response Message
            DataInputStream inputStream = new DataInputStream(connection.getInputStream());
            if (responseCode == 200) {
                String temp;
                String responseBody = "";
                while ((temp = inputStream.readLine()) != null) {
                    responseBody += temp;
                }
                callbackContext.success(responseBody);
            } else {
                callbackContext.error("Fail to get the response, response code: " + responseCode + ", response message: " + responseMessage);
            }

            inputStream.close();
        } catch (IOException e) {
            Log.d("request", e.getMessage());
        }
    }

    public void getRemoteDataExt(JSONObject obj) {
        String requestUrl;
        String id;
        String method;
        String body;
        String headers;
        try {
            //Request url
            requestUrl = obj.getString("url");

            //ID that correlates the request to the event
            id = obj.getString("id");

            //Request method
            method = obj.getString("method");

            //Request body
            body = obj.getString("body");

            //Request header
            headers = obj.getString("headers");

            String js = null;
            if (method == null || method.length() == 0) method = "GET";

            HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            boolean forceUTF8 = false;

            try {
                if (headers.length() > 0) {
                    String[] headerArray = headers.split("&");

                    //Set request header
                    for (String header : headerArray) {
                        String[] headerPair = header.split("=");
                        if (headerPair.length == 2) {
                            String field = headerPair[0];
                            String value = headerPair[1];
                            if (field != null && value != null) {
                                if (!"content-length".equals(field.toLowerCase())) {//skip Content-Length - causes error because it is managed by the request
                                    connection.setRequestProperty(field, value);
                                }

                                field = field.toLowerCase();
                                value = value.toLowerCase();
                                if (field.equals("content-type") && value.indexOf("charset") > -1 && value.indexOf("utf-8") > -1) {
                                    forceUTF8 = true;
                                }
                            }
                        }
                    }

                }

                if ("POST".equalsIgnoreCase(method)) {
                    connection.setRequestMethod(method);
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length());
                    DataOutputStream dStream = new DataOutputStream(connection.getOutputStream());
                    dStream.writeBytes(body);
                    dStream.flush();
                    dStream.close();
                }

                //inject response
                int statusCode = connection.getResponseCode();

                final StringBuilder response = new StringBuilder();
                try {
                    InputStream responseStream = connection.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(responseStream));
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                } catch(Exception e) {}

                String responseBody = null;
// how to handle UTF8 without EntityUtils?
//                if (forceUTF8) {
//                    responseBody = EntityUtils.toString(entity, "UTF-8");
//                } else {
//                    responseBody = EntityUtils.toString(entity);
//                }
                responseBody = response.toString();
                String responseMessage = connection.getResponseMessage();

                char[] bom = {0xef, 0xbb, 0xbf};
                //check for BOM characters, then strip if present
                if (responseBody.length() >= 3 && responseBody.charAt(0) == bom[0] && responseBody.charAt(1) == bom[1] && responseBody.charAt(2) == bom[2]) {
                    responseBody = responseBody.substring(3);
                }

                //escape existing backslashes
                responseBody = responseBody.replaceAll("\\\\", "\\\\\\\\");

                //escape internal double-quotes
                responseBody = responseBody.replaceAll("\"", "\\\\\"");
                responseBody = responseBody.replaceAll("'", "\\\\'");

                //replace linebreaks with \n
                responseBody = responseBody.replaceAll("\\r\\n|\\r|\\n", "\\\\n");

                StringBuilder extras = new StringBuilder("{");
                extras.append(String.format("status:'%d',", statusCode));

                String status = null;
                switch (statusCode) {
                    case 200:
                        status = "OK";
                        break;
                    case 201:
                        status = "CREATED";
                        break;
                    case 202:
                        status = "Accepted";
                        break;
                    case 203:
                        status = "Partial Information";
                        break;
                    case 204:
                        status = "No Response";
                        break;
                    case 301:
                        status = "Moved";
                        break;
                    case 302:
                        status = "Found";
                        break;
                    case 303:
                        status = "Method";
                        break;
                    case 304:
                        status = "Not Modified";
                        break;
                    case 400:
                        status = "Bad request";
                        break;
                    case 401:
                        status = "Unauthorized";
                        break;
                    case 402:
                        status = "PaymentRequired";
                        break;
                    case 403:
                        status = "Forbidden";
                        break;
                    case 404:
                        status = "Not found";
                        break;
                    case 500:
                        status = "Internal Error";
                        break;
                    case 501:
                        status = "Not implemented";
                        break;
                    case 502:
                        status = "Service temporarily overloaded";
                        break;
                    case 503:
                        status = "Gateway timeout";
                        break;
                }
                extras.append(String.format("statusText:'%s',", status));
                extras.append("headers: {");

                List<String> cookieData = new ArrayList<String>();
                Map<String, List<String>> allHeaders = connection.getHeaderFields();
                for (String key : allHeaders.keySet()) {
                    if(key == null) {
                        continue;
                    }
                    String value = connection.getHeaderField(key);
                    value = value.replaceAll("'", "\\\\'");
                    if (key.toLowerCase().equals("set-cookie"))
                        cookieData.add(value);
                    else
                        extras.append(String.format("'%s':'%s',", key, value));
                }

                String concatCookies = cookieData.toString();
                concatCookies = concatCookies.substring(0, concatCookies.length());
                extras.append(String.format("'Set-Cookie':'%s',", concatCookies.substring(1, concatCookies.length() - 1)));

                String cookieArray = "[";
                for (int i = 0; i < cookieData.size(); i++)
                    cookieArray += String.format("'%s',", cookieData.get(i));
                cookieArray += "]";

                extras.append("'All-Cookies': " + cookieArray);
                extras.append("} }");

                js = String.format(
                        "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.remote.data',true,true);e.success=true;e.id='%s';e.response='%s';e.extras=%s;document.dispatchEvent(e);",
                        id, responseBody, extras.toString());

            } catch (Exception ex) {
                js = String.format(
                        "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.remote.data',true,true);e.success=false;e.id='%s';e.response='';e.extras={};e.error='%s';document.dispatchEvent(e);",
                        id, ex.getMessage());
            } catch (OutOfMemoryError err) {
                js = String.format(
                        "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.remote.data',true,true);e.success=false;e.id='%s';e.response='';e.extras={};e.error='%s';document.dispatchEvent(e);",
                        id, err.getMessage());
            } finally {
                connection.disconnect();
            }


            injectJS(js);

        } catch (Exception e) {
            Log.d("getRemoteDataExt", e.getMessage());
        }
    }

    public void getRemoteDataWithID(String requestUrl, String requestMethod, String requestBody, int uuid, String successCallback, String errorCallback) {
        try {
            URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod(requestMethod);

            //Write requestBody
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(requestBody);
            outputStream.writeBytes("&uuid=" + uuid);
            outputStream.flush();
            outputStream.close();

            //Get response code and response message
            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            //Get response Message
            DataInputStream inputStream = new DataInputStream(connection.getInputStream());
            if (responseCode == 200) {
                String temp;
                String responseBody = "";
                while ((temp = inputStream.readLine()) != null) {
                    responseBody += temp;
                }
                //callbackContext.success(responseBody);
                String js = "javascript:" + successCallback + "(" + uuid + ", '" + responseBody + "');";
                injectJS(js);
            } else {
                //callbackContext.error("Fail to get the response, response code: " + responseCode + ", response message: " + responseMessage);
                String js = "javascript:" + errorCallback + "(" + uuid + ", '" + "Fail to get the response" + "');";
                injectJS(js);
            }

            inputStream.close();
        } catch (IOException e) {
            Log.d("request", e.getMessage());
        }
    }

    public void getRemoteDataWithID(String requestUrl, String requestMethod, String requestBody, int uuid, CallbackContext callbackContext) {
        try {
            URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod(requestMethod);

            //Write requestBody
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(requestBody);
            outputStream.writeBytes("&uuid=" + uuid);
            outputStream.flush();
            outputStream.close();

            //Get response code and response message
            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            //Get response Message
            DataInputStream inputStream = new DataInputStream(connection.getInputStream());
            if (responseCode == 200) {
                String temp;
                String responseBody = "";
                while ((temp = inputStream.readLine()) != null) {
                    responseBody += temp;
                }
                callbackContext.success(uuid + ", " + responseBody);
                //String js = "javascript:" + successCallback + "(" + uuid + ", '" + responseBody + "');";
                //injectJS(js);
            } else {
                callbackContext.error(uuid + ", Fail to get the response, response code: " + responseCode + ", response message: " + responseMessage);
                //String js = "javascript:" + errorCallback + "(" + uuid + ", '" + "Fail to get the response" + "');";
                //injectJS(js);
            }

            inputStream.close();
        } catch (IOException e) {
            Log.d("request", e.getMessage());
        }
    }

    public void hideStatusBar() {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            }
        });
    }

    public void launchExternal(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        activity.startActivity(intent);
    }

    public void managePower(boolean shouldStayOn, boolean onlyWhenPluggedIn) {

        //clear the wake lock
        if (wl != null) {
            wl.release();
            wl = null;
        }
        //unregister the batteryChangeReceiver
        if (batteryChangeReceiver != null) {
            activity.unregisterReceiver(batteryChangeReceiver);
            batteryChangeReceiver = null;
        }

        //if shouldStayOn is false we are done
        if (!shouldStayOn) {
            return;
        }

        //if onlyWhenPluggedIn is true, then we should monitor whether we are plugged in
        if (onlyWhenPluggedIn) {
            registerBatteryChangeReceiver();
            //otherwise, we should stay on all the time so get the wakeLock
        } else {
            aquireWakeLock();
        }
    }

    public void runInstallNativeApp(String appname, String runcommand, String installurl, String bundleid) {
        Intent LaunchIntent = null;
        strStoreUrl = installurl;

        try {
            LaunchIntent = activity.getPackageManager().getLaunchIntentForPackage(bundleid);
        } catch (Exception e) {
        }

        if (LaunchIntent == null) {
            AlertDialog.Builder alertBldr = new AlertDialog.Builder(activity);
            alertBldr.setMessage("You don't have " + appname + " installed. Please touch OK to install from the Market.");
            alertBldr.setTitle("Missing Application");
            alertBldr.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    launchStore();
                }
            });
            alertBldr.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            alertBldr.show();
        } else {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(runcommand)));
            strStoreUrl = "";
        }
    }

    private void launchStore() {
        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(strStoreUrl)));
        strStoreUrl = "";
    }

    public void sendEmail(String body, String to, String subject, boolean ishtml, String cc, String bcc) {

        String toArray[] = to.split(",");
        String ccArray[] = cc.split(",");
        String bccArray[] = bcc.split(",");

        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE); // it's not ACTION_SEND
        if (ishtml) {
            //Android default mail clients poorly support html formatted mail :(
            //intent.setType("text/html");
            //intent.putExtra(Intent.EXTRA_TEXT, Html.fromHtml(body,null,null));
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, body);
        } else {
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, body);
        }

        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        if (toArray.length > 0 && !toArray[0].equals("")) {
            intent.putExtra(Intent.EXTRA_EMAIL, toArray);
        }
        if (ccArray.length > 0 && !ccArray[0].equals("")) {
            intent.putExtra(Intent.EXTRA_CC, ccArray);
        }
        if (bccArray.length > 0 && !bccArray[0].equals("")) {
            intent.putExtra(Intent.EXTRA_BCC, bccArray);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); //return user to app after sending mail
        activity.startActivity(intent);
    }

    public void sendSMS(String body, String to) {
        Uri uri = Uri.parse("smsto:" + to);
        Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
        intent.putExtra("sms_body", body);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    public void setAutoRotate(boolean shouldRotate) {
        shouldAutoRotate = shouldRotate;
        updateOrientation();
    }

    @JavascriptInterface
    public void setRotateOrientation(String orientation) {
        rotateOrientation = orientation;
        updateOrientation();
    }

    private void updateOrientation() {
        if (rotateOrientation.equalsIgnoreCase("landscape")) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else if (rotateOrientation.equalsIgnoreCase("portrait")) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            activity.setRequestedOrientation(shouldAutoRotate ? ActivityInfo.SCREEN_ORIENTATION_SENSOR :
                    activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ?
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    public void setBasicAuthentication(String host, String username, String password) {
        Method m;
        try {
            m = webView.getClass().getMethod("setHttpAuthUsernamePassword", String.class, String.class, String.class, String.class);
            if (m != null) {
                m.invoke(webView, host, "", username, password);
            }
        } catch (Exception e) {
            //TODO: should handle missing method for Crosswalk
        }
        //webView.setHttpAuthUsernamePassword(host, "", username, password);
    }

    public void showRemoteSite(final String strURL, final int closeX, final int closeY, final int closeW, final int closeH) {
        showRemoteSite(strURL, closeX, closeY, closeX, closeY, closeW, closeH);
    }

    public void showRemoteSite(final String strURL, final int closeX_pt, final int closeY_pt, final int closeX_ls, final int closeY_ls, final int closeW, final int closeH) {
        showRemoteSite(strURL, closeX_pt, closeY_pt, closeX_ls, closeY_ls, closeW, closeH, null);
    }

    public void showRemoteSite(final String strURL, final int closeX_pt, final int closeY_pt, final int closeX_ls, final int closeY_ls, final int closeW, final int closeH, final String closeImage) {
        if (strURL == null || strURL.length() == 0) return;

        remoteCloseXPort = closeX_pt;
        remoteCloseYPort = closeY_pt;
        remoteCloseXLand = closeX_ls;
        remoteCloseYLand = closeY_ls;

        //hack to adjust image size
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);

        remoteCloseW = (int) ((float) closeW * dm.density);
        remoteCloseH = (int) ((float) closeH * dm.density);

        //Set position, width, height of closeImage according to currentOrientation
        if (this.getOrientation() == 0 || this.getOrientation() == 180) {
            //Portrait
            AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(remoteCloseW == 0 ? 48 : remoteCloseW, remoteCloseH == 0 ? 48 : remoteCloseH, remoteCloseXPort, remoteCloseYPort);
            remoteClose.setLayoutParams(params);
        } else {
            AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(remoteCloseW == 0 ? 48 : remoteCloseW, remoteCloseH == 0 ? 48 : remoteCloseH, remoteCloseXLand, remoteCloseYLand);
            remoteClose.setLayoutParams(params);
        }

        activity.runOnUiThread(new Runnable() {

            public void run() {

                if (remoteView == null) {

                    remoteView = new WebView(activity);
                    remoteView.setInitialScale(0);
                    remoteView.setVerticalScrollBarEnabled(false);
                    remoteView.setWebViewClient(new WebViewClient());
                    final WebSettings settings = remoteView.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setJavaScriptCanOpenWindowsAutomatically(true);
                    settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

                    remoteLayout.addView(remoteView, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER));

                    remoteView.requestFocusFromTouch();

                    remoteView.setOnKeyListener(new View.OnKeyListener() {
                        @Override
                        public boolean onKey(View v, int keyCode, KeyEvent event) {
                            if (event.getAction() != KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                                remoteClose.performClick();
                                return true;
                            } else {
                                return false;
                            }
                        }
                    });
                }
                // load the url
                remoteView.loadUrl(strURL);
                // show the view
                remoteLayout.setVisibility(View.VISIBLE);
                // set the flag
                isShowingRemoteSite = true;
                // isShowingRemoteSite = true;
                // get focus
                remoteView.requestFocus(View.FOCUS_DOWN);
                remoteClose.bringToFront();
            }
        });

    }

    public void closeRemoteSite() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                remoteLayout.setVisibility(View.GONE);
                remoteView.loadUrl("about:blank");
            }
        });
        String remoteCloseEvent = "javascript: var e =document.createEvent('Events');e.initEvent('intel.xdk.device.remote.close',true,true);document.dispatchEvent(e);";
        injectJS(remoteCloseEvent);

        isShowingRemoteSite = false;
    }

    @Override
    public void onResume(boolean multitasking) {
        Log.d("resume", "App is resumed.");
        String js = "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.resume');e.success=true;document.dispatchEvent(e);";
        injectJS(js);

        //re-register listeners if they had been previously registered
        if(screenStatusReceiver != null) {
            registerScreenStatusReceiver();
        }
        if(batteryChangeReceiver != null){
            registerBatteryChangeReceiver();
        }

    }

    @Override
    public void onPause(boolean multitasking) {
        Log.d("suspend", "App is suspended.");
        String js = "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.suspend');e.success=true;document.dispatchEvent(e);";
        injectJS(js);

        //unregister listeners
        if(screenStatusReceiver != null) {
            activity.unregisterReceiver(screenStatusReceiver);
        }
        if(batteryChangeReceiver != null){
            activity.unregisterReceiver(batteryChangeReceiver);
        }

    }

    public void mainViewExecute(String js) {
        injectJS("javascript: " + js);
    }

    public void updateConnection() {
        String currentConnection = getConnection();
        String connectionType = "javascript: try{intel.xdk.device.connection =  \"" + currentConnection + "\";}catch(e){}var e =document.createEvent('Events');e.initEvent('intel.xdk.device.connection.update',true,true);document.dispatchEvent(e);";
        injectJS(connectionType);
    }

    public void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setText(text);
    }

    private void aquireWakeLock() {
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
        try {
            wl.acquire();
        } catch (Exception e) {
        }
    }

    public void openWebPage(String url) {
        Log.d("device", "openWebPage() is called, url: " + url);
        try {
            webView.loadUrl(url);
        } catch (Exception e) {
            Log.d("openWebPage", e.getMessage());
        }
    }

    public void initialize() {
        String connection = getConnection();
        String model = getModel();
        int orientation = getOrientation();
        int initialOrientation = getInitialOrientation();
        String osversion = getOSVersion();
        String phonegapversion = getPGVersion();
        String platform = getPlatform();
        String queryString = getQueryString();

        //getLastStation() is to do.
        String lastStation = null;

        //Previously initialized based on appconfig.xml - to be removed.
        boolean hasCaching = false;
        boolean hasStreaming = false;
        boolean hasAnalytics = false;
        boolean hasPush = false;
        boolean hasUpdates = false;

        //Event intel.xdk.device.init is just an internal event, which will be fired once properties have been bound to intel.xdk.device.
        //The use of this event is to ensure "deviceready" event wouldn't be fired before properties binding completed.
        //Users should not try to handle this event.
        String js = "javascript:var e = document.createEvent('Events');e.initEvent('intel.xdk.device.init', true, true);e.success=true;";

        js += "if(this.intel === undefined){this.intel = {};intel.xdk = {};intel.xdk.device = {};}";
        js += "else if(this.intel.xdk === undefined){ this.intel.xdk = {};intel.xdk.device = {};}";
        js += "else if(this.intel.xdk.device === undefined){intel.xdk.device == {};}";

        js += "intel.xdk.device.connection='" + connection + "';";
        js += "intel.xdk.device.model='" + model + "';";
        js += "intel.xdk.device.initialOrientation='" + initialOrientation + "';";
        js += "intel.xdk.device.orientation='" + orientation + "';";
        js += "intel.xdk.device.osversion='" + osversion + "';";
        js += "intel.xdk.device.phonegapversion='" + phonegapversion + "';";
        js += "intel.xdk.device.platform='" + platform + "';";
        js += "intel.xdk.device.queryString='" + queryString + "';";

        js += "intel.xdk.device.hasCaching='" + hasCaching + "';";
        js += "intel.xdk.device.hasStreaming='" + hasStreaming + "';";
        js += "intel.xdk.device.hasAnalytics='" + hasAnalytics + "';";
        js += "intel.xdk.device.hasPush='" + hasPush + "';";
        js += "intel.xdk.device.hasUpdates='" + hasUpdates + "';";
        js += "intel.xdk.device.lastStation='" + lastStation + "';";

        js += "document.dispatchEvent(e);";
        injectJS(js);
    }

    private String getConnection() {
        WifiManager wifiMgr = (WifiManager) activity.getSystemService(Context.WIFI_SERVICE);
        if (wifiMgr.isWifiEnabled() == true) {
            return "wifi";
        }

        TelephonyManager telMgr = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
        int data = telMgr.getDataState();
        if (data == TelephonyManager.DATA_DISCONNECTED || data == TelephonyManager.DATA_SUSPENDED)
            return "none";
        else
            return "cell";
    }

    private boolean getHasCaching() {
        return false;
    }

    private int getInitialOrientation() {
        return getOrientation();
    }

    private int getOrientation() {
        //return (activity.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT)? 0 : -90 ;
        return this.displayOrientation;
    }

    private String getModel() {
        return android.os.Build.MODEL;
    }

    private String getOSVersion() {
        return android.os.Build.VERSION.RELEASE;
    }

    private String getPGVersion() {
        return phonegap;
    }

    private String getPlatform() {
        return platform;
    }

    private String getQueryString() {
        if (activity.getIntent() != null && activity.getIntent().getData() != null && activity.getIntent().getData().getQuery() != null) {
            return activity.getIntent().getData().getQuery();
        } else {
            return "";
        }
    }

    @TargetApi(19)
    private void injectJS(final String js) {
        activity.runOnUiThread(new Runnable() {

            public void run() {
                try {
                    if (evaluateJavascript != null) {
                        evaluateJavascript.invoke(webView, js.replaceFirst("javascript:", ""), emptyVC);
                    } else {
                        if (js.startsWith("javascript:") && sendJavascript != null) {
                            webView.sendJavascript(js.replaceFirst("javascript:", ""));
                        } else {
                            webView.loadUrl(js);
                        }
                    }
                } catch (Exception e) {
                    // just log for now
                    Log.e("", "!!!WebView.loadUrl failed!!!", e);
                }

            }

        });
    }

    private void registerScreenStatusReceiver() {

        if(screenStatusReceiver == null) {
            //Listener to the screen unlock event.

            screenStatusReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                        Log.d("screen_on", "Screen is on.");
                    } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                        Log.d("screen_lock", "Screen is off");
                        String js = "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.pause');e.success=true;document.dispatchEvent(e);";
                        injectJS(js);
                    } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                        Log.d("user_present", "User is present.");
                        String js = "javascript: var e = document.createEvent('Events');e.initEvent('intel.xdk.device.continue');e.success=true;document.dispatchEvent(e);";
                        injectJS(js);
                    }

                }

            };
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        activity.registerReceiver(screenStatusReceiver, filter);
    }

    private void registerBatteryChangeReceiver() {

        if(batteryChangeReceiver == null) {

            batteryChangeReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {
                    int batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    boolean isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL;

                    if (isCharging) {
                        if (wl == null) {
                            aquireWakeLock();
                        }
                    } else {
                        if (wl != null) {
                            wl.release();
                            wl = null;
                        }
                    }
                }

            };
        }

        activity.registerReceiver(batteryChangeReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

}