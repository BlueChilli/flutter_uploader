package com.bluechilli.flutteruploader;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.reflect.TypeToken;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.view.FlutterCallbackInformation;
import io.flutter.view.FlutterMain;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class UploadWorker extends ListenableWorker
    implements CountProgressListener, MethodCallHandler {
  public static final String ARG_URL = "url";
  public static final String ARG_METHOD = "method";
  public static final String ARG_HEADERS = "headers";
  public static final String ARG_DATA = "data";
  public static final String ARG_FILES = "files";
  public static final String ARG_REQUEST_TIMEOUT = "requestTimeout";
  public static final String ARG_SHOW_NOTIFICATION = "showNotification";
  public static final String ARG_BINARY_UPLOAD = "binaryUpload";
  public static final String ARG_UPLOAD_REQUEST_TAG = "tag";
  public static final String ARG_ID = "primaryId";
  public static final String EXTRA_STATUS_CODE = "statusCode";
  public static final String EXTRA_STATUS = "status";
  public static final String EXTRA_ERROR_MESSAGE = "errorMessage";
  public static final String EXTRA_ERROR_CODE = "errorCode";
  public static final String EXTRA_ERROR_DETAILS = "errorDetails";
  public static final String EXTRA_RESPONSE = "response";
  public static final String EXTRA_RESPONSE_FILE = "response_file";
  public static final String EXTRA_ID = "id";
  public static final String EXTRA_HEADERS = "headers";
  private static final String TAG = UploadWorker.class.getSimpleName();
  private static final String CHANNEL_ID = "FLUTTER_UPLOADER_NOTIFICATION";
  private static final int UPDATE_STEP = 0;
  private static final int DEFAULT_ERROR_STATUS_CODE = 500;

  private NotificationCompat.Builder builder;
  private boolean showNotification;
  private String msgStarted, msgInProgress, msgCanceled, msgFailed, msgComplete;
  private int lastProgress = 0;
  private int lastNotificationProgress = 0;
  private String tag;
  private int primaryId;
  private Call call;
  private boolean isCancelled = false;

  private Context context;

  public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);

    this.context = context;
  }

  @Nullable private static FlutterEngine engine;

  @Nullable private MethodChannel backgroundChannel;

  private static final String BACKGROUND_CHANNEL_NAME =
      "com.bluechilli.flutteruploader/background_channel_uploader";
  private static final String BACKGROUND_CHANNEL_INITIALIZED = "backgroundChannelInitialized";

  private Executor backgroundExecutor = Executors.newSingleThreadExecutor();

  @NonNull
  @Override
  public ListenableFuture<Result> startWork() {
    startEngine();

    return CallbackToFutureAdapter.getFuture(
        completer -> {
          backgroundExecutor.execute(
              () -> {
                try {
                  final Result result = doWorkInternal();
                  completer.set(result);
                } catch (Throwable e) {
                  completer.setException(e);
                } finally {
                  // Do not destroy the engine at this very moment.
                  // Keep it running in the background for just a little while.
//                  stopEngine();
                }
              });

          return getId().toString();
        });
  }

  @NonNull
  public Result doWorkInternal() {

    String url = getInputData().getString(ARG_URL);
    String method = getInputData().getString(ARG_METHOD);
    int timeout = getInputData().getInt(ARG_REQUEST_TIMEOUT, 3600);
    showNotification = getInputData().getBoolean(ARG_SHOW_NOTIFICATION, false);
    boolean isBinaryUpload = getInputData().getBoolean(ARG_BINARY_UPLOAD, false);
    String headersJson = getInputData().getString(ARG_HEADERS);
    String parametersJson = getInputData().getString(ARG_DATA);
    String filesJson = getInputData().getString(ARG_FILES);
    tag = getInputData().getString(ARG_UPLOAD_REQUEST_TAG);
    primaryId = getInputData().getInt(ARG_ID, 0);

    if (tag == null) {
      tag = getId().toString();
    }

    int statusCode = 200;
    Resources res = context.getResources();
    msgStarted = res.getString(R.string.flutter_uploader_notification_started);
    msgInProgress = res.getString(R.string.flutter_uploader_notification_in_progress);
    msgCanceled = res.getString(R.string.flutter_uploader_notification_canceled);
    msgFailed = res.getString(R.string.flutter_uploader_notification_failed);
    msgComplete = res.getString(R.string.flutter_uploader_notification_complete);

    try {
      Map<String, String> headers = null;
      Map<String, String> parameters = null;
      List<FileItem> files = new ArrayList<>();
      Gson gson = new Gson();
      Type type = new TypeToken<Map<String, String>>() {}.getType();
      Type fileItemType = new TypeToken<List<FileItem>>() {}.getType();

      if (headersJson != null) {
        headers = gson.fromJson(headersJson, type);
      }

      if (parametersJson != null) {
        parameters = gson.fromJson(parametersJson, type);
      }

      if (filesJson != null) {
        files = gson.fromJson(filesJson, fileItemType);
      }

      final RequestBody innerRequestBody;

      if (isBinaryUpload) {
        final FileItem item = files.get(0);
        File file = new File(item.getPath());

        if (!file.exists()) {
          return Result.failure(
              createOutputErrorData(
                  UploadStatus.FAILED,
                  DEFAULT_ERROR_STATUS_CODE,
                  "invalid_files",
                  "There are no items to upload",
                  null));
        }

        String mimeType = GetMimeType(item.getPath());
        MediaType contentType = MediaType.parse(mimeType);
        innerRequestBody = RequestBody.create(file, contentType);
      } else {
        MultipartBody.Builder formRequestBuilder = prepareRequest(parameters, null);
        int fileExistsCount = 0;
        for (FileItem item : files) {
          File file = new File(item.getPath());
          Log.d(TAG, "attaching file: " + item.getPath());

          if (file.exists() && file.isFile()) {
            fileExistsCount++;
            String mimeType = GetMimeType(item.getPath());
            MediaType contentType = MediaType.parse(mimeType);
            RequestBody fileBody = RequestBody.create(file, contentType);
            formRequestBuilder.addFormDataPart(item.getFieldname(), item.getFilename(), fileBody);
          } else {
            Log.d(TAG, "File does not exists -> file:" + item.getPath());
          }
        }

        if (fileExistsCount <= 0) {
          return Result.failure(
              createOutputErrorData(
                  UploadStatus.FAILED,
                  DEFAULT_ERROR_STATUS_CODE,
                  "invalid_files",
                  "There are no items to upload",
                  null));
        }

        innerRequestBody = formRequestBuilder.build();
      }

      RequestBody requestBody = new CountingRequestBody(innerRequestBody, getId().toString(), this);
      Request.Builder requestBuilder = new Request.Builder();

      if (headers != null) {

        for (String key : headers.keySet()) {

          String header = headers.get(key);

          if (header != null && !header.isEmpty()) {
            requestBuilder = requestBuilder.addHeader(key, header);
          }
        }
      }

      if (!URLUtil.isValidUrl(url)) {
        return Result.failure(
            createOutputErrorData(
                UploadStatus.FAILED,
                DEFAULT_ERROR_STATUS_CODE,
                "invalid_url",
                "url is not a valid url",
                null));
      }

      requestBuilder.addHeader("Accept", "application/json; charset=utf-8");

      Request request;

      switch (method.toUpperCase()) {
        case "PUT":
          request = requestBuilder.url(url).put(requestBody).build();
          break;
        case "PATCH":
          request = requestBuilder.url(url).patch(requestBody).build();

          break;
        default:
          request = requestBuilder.url(url).post(requestBody).build();

          break;
      }

      buildNotification(context);

      Log.d(TAG, "Start uploading for " + tag);

      OkHttpClient client =
          new OkHttpClient.Builder()
              .connectTimeout((long) timeout, TimeUnit.SECONDS)
              .writeTimeout((long) timeout, TimeUnit.SECONDS)
              .readTimeout((long) timeout, TimeUnit.SECONDS)
              .build();

      call = client.newCall(request);
      Response response = call.execute();
      statusCode = response.code();
      Headers rheaders = response.headers();
      Map<String, String> outputHeaders = new HashMap<>();

      boolean hasJsonResponse = true;

      String responseContentType = rheaders.get("content-type");

      ResponseBody body = response.body();

      hasJsonResponse =
          responseContentType != null && responseContentType.contains("json") && body != null;

      for (String name : rheaders.names()) {
        String value = rheaders.get(name);
        if (value != null) {
          outputHeaders.put(name, value);
        } else {
          outputHeaders.put(name, "");
        }
      }

      String responseHeaders = gson.toJson(outputHeaders);
      String responseString = "";
      if (body != null) {
        responseString = body.string();
      }

      if (!response.isSuccessful()) {
        if (showNotification) {
          updateNotification(context, tag, UploadStatus.FAILED, 0, null);
        }

        return Result.failure(
            createOutputErrorData(
                UploadStatus.FAILED, statusCode, "upload_error", responseString, null));
      }

      Data.Builder builder =
          new Data.Builder()
              .putString(EXTRA_ID, getId().toString())
              .putInt(EXTRA_STATUS, UploadStatus.COMPLETE)
              .putInt(EXTRA_STATUS_CODE, statusCode)
              .putString(EXTRA_HEADERS, responseHeaders);

      if (hasJsonResponse) {
        builder.putString(EXTRA_RESPONSE, responseString);
      }

      Data outputData;
      try {
        outputData = builder.build();
      } catch (IllegalStateException e) {
        if (responseString.isEmpty()) {
          // Managed to break it with an empty string.
          throw e;
        }

        Log.d(
            TAG,
            "IllegalStateException while building a outputData object. Replace response with on-disk reference.");
        builder.putString(EXTRA_RESPONSE, null);

        File responseFile = writeResponseToTemporaryFile(context, responseString);
        if (responseFile != null) {
          builder.putString(EXTRA_RESPONSE_FILE, responseFile.getAbsolutePath());
        }

        outputData = builder.build();
      }

      if (showNotification) {
        updateNotification(context, tag, UploadStatus.COMPLETE, 0, null);
      }

      return Result.success(outputData);

    } catch (JsonIOException ex) {
      return handleException(context, ex, "json_error");
    } catch (UnknownHostException ex) {
      return handleException(context, ex, "unknown_host");
    } catch (IOException ex) {
      return handleException(context, ex, "io_error");
    } catch (Exception ex) {
      return handleException(context, ex, "upload error");
    } finally {
      call = null;
    }
  }

  private File writeResponseToTemporaryFile(Context context, String body) {
    FileOutputStream fos = null;
    try {
      File tempFile = File.createTempFile("flutter_uploader", null, context.getCacheDir());
      fos = new FileOutputStream(tempFile);
      fos.write(body.getBytes());
      fos.close();
      return tempFile;
    } catch (Throwable e) {
      if (fos != null) {
        try {
          fos.close();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private void startEngine() {
    long callbackHandle = SharedPreferenceHelper.getCallbackHandle(context);

    Log.d(TAG, "callbackHandle: " + callbackHandle);

    if (callbackHandle != -1L && engine == null) {
      engine = new FlutterEngine(context);
      FlutterMain.ensureInitializationComplete(context, null);

      FlutterCallbackInformation callbackInfo =
          FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
      String dartBundlePath = FlutterMain.findAppBundlePath();

      engine
          .getDartExecutor()
          .executeDartCallback(
              new DartExecutor.DartCallback(context.getAssets(), dartBundlePath, callbackInfo));

      backgroundChannel = new MethodChannel(engine.getDartExecutor(), BACKGROUND_CHANNEL_NAME);
      backgroundChannel.setMethodCallHandler(this);
    }
  }

  private void stopEngine() {
    Log.d(TAG, "Destroying worker engine.");

    if (backgroundChannel != null) {
      backgroundChannel.setMethodCallHandler(null);
      backgroundChannel = null;
    }

    if (engine != null) {
      try {
        engine.destroy();
      } catch (Throwable e) {
        Log.e(TAG, "Can not destroy engine", e);
      }
      engine = null;
    }
  }

  private Result handleException(Context context, Exception ex, String code) {

    return null;
  }

  private Result handleException(Context context, Exception ex, String code) {
    Log.e(TAG, "exception encountered", ex);

    int finalStatus = isCancelled ? UploadStatus.CANCELED : UploadStatus.FAILED;
    String finalCode = isCancelled ? "upload_cancelled" : code;

    if (showNotification) {
      updateNotification(context, tag, finalStatus, 0, null);
    }

    return Result.failure(
        createOutputErrorData(
            finalStatus,
            500,
            finalCode,
            ex.toString(),
            getStacktraceAsStringList(ex.getStackTrace())));
  }

  private String GetMimeType(String url) {
    String type = "*/*";
    String extension = MimeTypeMap.getFileExtensionFromUrl(url);
    try {
      if (extension != null && !extension.isEmpty()) {
        type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
      }
    } catch (Exception ex) {
      Log.d(TAG, "UploadWorker - GetMimeType", ex);
    }

    return type;
  }

  private MultipartBody.Builder prepareRequest(Map<String, String> parameters, String boundary) {

    MultipartBody.Builder requestBodyBuilder =
        boundary != null && !boundary.isEmpty()
            ? new MultipartBody.Builder(boundary)
            : new MultipartBody.Builder();

    requestBodyBuilder.setType(MultipartBody.FORM);

    if (parameters == null) return requestBodyBuilder;

    for (String key : parameters.keySet()) {
      String parameter = parameters.get(key);
      if (parameter != null) {
        requestBodyBuilder.addFormDataPart(key, parameter);
      }
    }

    return requestBodyBuilder;
  }

  private void sendUpdateProcessEvent(Context context, int status, int progress) {
    setProgressAsync(
        new Data.Builder().putInt("status", status).putInt("progress", progress).build());
  }

  private Data createOutputErrorData(
      int status, int statusCode, String code, String message, String[] details) {
    return new Data.Builder()
        .putInt(UploadWorker.EXTRA_STATUS_CODE, statusCode)
        .putInt(UploadWorker.EXTRA_STATUS, status)
        .putString(UploadWorker.EXTRA_ERROR_CODE, code)
        .putString(UploadWorker.EXTRA_ERROR_MESSAGE, message)
        .putStringArray(UploadWorker.EXTRA_ERROR_DETAILS, details)
        .build();
  }

  @Override
  public void OnProgress(String taskId, long bytesWritten, long contentLength) {
    double p = ((double) bytesWritten / (double) contentLength) * 100;
    int progress = (int) Math.round(p);
    boolean running = isRunning(progress, lastProgress, UPDATE_STEP);
    Log.d(
        TAG,
        "taskId: "
            + getId().toString()
            + ", bytesWritten: "
            + bytesWritten
            + ", contentLength: "
            + contentLength
            + ", progress: "
            + progress
            + ", lastProgress: "
            + lastProgress);
    if (running) {
      sendUpdateProcessEvent(context, UploadStatus.RUNNING, progress);
      boolean shouldSendNotification = isRunning(progress, lastNotificationProgress, 10);
      if (showNotification && shouldSendNotification) {
        updateNotification(context, tag, UploadStatus.RUNNING, progress, null);
        lastNotificationProgress = progress;
      }

      lastProgress = progress;
    }
  }

  @Override
  public void onStopped() {
    super.onStopped();
    Log.d(TAG, "UploadWorker - Stopped");
    try {
      isCancelled = true;
      if (call != null && !call.isCanceled()) {
        call.cancel();
      }
    } catch (Exception ex) {
      Log.d(TAG, "Upload Request cancelled", ex);
    }
  }

  @Override
  public void OnError(String taskId, String code, String message) {
    Log.d(
        TAG,
        "Failed to upload - taskId: "
            + getId().toString()
            + ", code: "
            + code
            + ", error: "
            + message);
    sendUpdateProcessEvent(context, UploadStatus.FAILED, -1);
  }

  private void buildNotification(Context context) {
    // Make a channel if necessary
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // Create the NotificationChannel, but only on API 26+ because
      // the NotificationChannel class is new and not in the support library

      CharSequence name = context.getApplicationInfo().loadLabel(context.getPackageManager());
      int importance = NotificationManager.IMPORTANCE_DEFAULT;
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
      channel.setSound(null, null);

      // Add the channel
      NotificationManager notificationManager = context.getSystemService(NotificationManager.class);

      if (notificationManager != null) {
        notificationManager.createNotificationChannel(channel);
      }
    }

    // Create the notification
    builder =
        new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
  }

  private void updateNotification(
      Context context, String title, int status, int progress, PendingIntent intent) {
    builder.setContentTitle(title);
    builder.setContentIntent(intent);

    boolean shouldUpdate = false;

    if (status == UploadStatus.RUNNING) {
      shouldUpdate = true;
      builder.setOngoing(true);
      builder
          .setContentText(progress == 0 ? msgStarted : msgInProgress)
          .setProgress(100, progress, progress == 0);
    } else if (status == UploadStatus.CANCELED) {
      shouldUpdate = true;
      builder.setOngoing(false);
      builder.setContentText(msgCanceled).setProgress(0, 0, false);
    } else if (status == UploadStatus.FAILED) {
      shouldUpdate = true;
      builder.setOngoing(false);
      builder.setContentText(msgFailed).setProgress(0, 0, false);
    } else if (status == UploadStatus.COMPLETE) {
      shouldUpdate = true;
      builder.setOngoing(false);
      builder.setContentText(msgComplete).setProgress(0, 0, false);
    }

    // Show the notification
    if (showNotification && shouldUpdate) {
      NotificationManagerCompat.from(context)
          .notify(getId().toString(), primaryId, builder.build());
    }
  }

  private boolean isRunning(int currentProgress, int previousProgress, int step) {
    int prev = previousProgress + step;
    return (currentProgress == 0 || currentProgress > prev || currentProgress >= 100)
        && currentProgress != previousProgress;
  }

  private String[] getStacktraceAsStringList(StackTraceElement[] stacktrace) {
    List<String> output = new ArrayList<>();

    if (stacktrace == null || stacktrace.length == 0) {
      return null;
    }

    for (StackTraceElement stackTraceElement : stacktrace) {
      output.add(stackTraceElement.toString());
    }

    return output.toArray(new String[0]);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    // To be considered - if we actually need a background engine, for anything.
  }
}
