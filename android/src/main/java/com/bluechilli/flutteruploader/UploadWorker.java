package com.bluechilli.flutteruploader;

import android.content.Context;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.reflect.TypeToken;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.view.FlutterCallbackInformation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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

public class UploadWorker extends ListenableWorker implements CountProgressListener {
  public static final String ARG_URL = "url";
  public static final String ARG_METHOD = "method";
  public static final String ARG_HEADERS = "headers";
  public static final String ARG_DATA = "data";
  public static final String ARG_FILES = "files";
  public static final String ARG_REQUEST_TIMEOUT = "requestTimeout";
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
  private static final int UPDATE_STEP = 0;
  private static final int DEFAULT_ERROR_STATUS_CODE = 500;

  private String tag;
  private Call call;
  private boolean isCancelled = false;

  private Context context;

  public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.backgroundExecutor = UploadExecutorService.getExecutorService(context);
    this.context = context;
  }

  @Nullable private static FlutterEngine engine;

  private Executor backgroundExecutor;

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
    boolean isBinaryUpload = getInputData().getBoolean(ARG_BINARY_UPLOAD, false);
    String headersJson = getInputData().getString(ARG_HEADERS);
    String parametersJson = getInputData().getString(ARG_DATA);
    String filesJson = getInputData().getString(ARG_FILES);
    tag = getInputData().getString(ARG_UPLOAD_REQUEST_TAG);

    if (tag == null) {
      tag = getId().toString();
    }

    int statusCode = 200;

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

        MediaType contentType = null;
        if (headers == null || (headers != null && headers.get("Content-Type") == null && headers.get("content-type") == null)) {
          String mimeType = GetMimeType(item.getPath());
          contentType = MediaType.parse(mimeType);
        }
        innerRequestBody = RequestBody.create(file, contentType);
      } else {
        MultipartBody.Builder formRequestBuilder = prepareRequest(parameters, null);
        int fileExistsCount = 0;
        for (FileItem item : files) {
          File file = new File(item.getPath());
          Log.d(TAG, "attaching file: " + item.getPath());

          if (file.exists() && file.isFile()) {
            fileExistsCount++;
            MediaType contentType = null;
            if (headers == null || (headers != null && headers.get("Content-Type") == null && headers.get("content-type") == null)) {
              String mimeType = GetMimeType(item.getPath());
              contentType = MediaType.parse(mimeType);
            }
            RequestBody fileBody = RequestBody.create(file, contentType);
            formRequestBuilder.addFormDataPart(item.getFieldname(), file.getName(), fileBody);
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

      requestBuilder.addHeader("Accept", "*/*");

      if (headers != null) {
        for (String key : headers.keySet()) {
          String header = headers.get(key);
          if (header != null && !header.isEmpty()) {
            requestBuilder = requestBuilder.header(key, header);
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
            "IllegalStateException while building a outputData object. Replace response with"
                + " on-disk reference.");
        builder.putString(EXTRA_RESPONSE, null);

        File responseFile = writeResponseToTemporaryFile(context, responseString);
        if (responseFile != null) {
          builder.putString(EXTRA_RESPONSE_FILE, responseFile.getAbsolutePath());
        }

        outputData = builder.build();
      }

      return Result.success(outputData);
    } catch (ProtocolException ex) {
      if (isCancelled) {
        return Result.failure();
      }
      return handleException(context, ex, "protocol");
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

    return null;
  }

  private void startEngine() {
    long callbackHandle = SharedPreferenceHelper.getCallbackHandle(context);

    Log.d(TAG, "callbackHandle: " + callbackHandle);

    if (callbackHandle != -1L && engine == null) {
      engine = new FlutterEngine(context);
      FlutterLoader flutterLoader = FlutterInjector.instance().flutterLoader();
      flutterLoader.ensureInitializationComplete(context, null);

      FlutterCallbackInformation callbackInfo =
          FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
      String dartBundlePath = flutterLoader.findAppBundlePath();

      engine
          .getDartExecutor()
          .executeDartCallback(
              new DartExecutor.DartCallback(context.getAssets(), dartBundlePath, callbackInfo));
    }
  }

  private void stopEngine() {
    Log.d(TAG, "Destroying worker engine.");

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
    Log.e(TAG, "exception encountered", ex);

    int finalStatus = isCancelled ? UploadStatus.CANCELED : UploadStatus.FAILED;
    String finalCode = isCancelled ? "upload_cancelled" : code;

    return Result.failure(
        createOutputErrorData(
            finalStatus,
            500,
            finalCode,
            ex.toString(),
            getStacktraceAsStringList(ex.getStackTrace())));
  }

  private String GetMimeType(String url) {
    String type = "application/octet-stream";
    String extension = MimeTypeMap.getFileExtensionFromUrl(url);
    try {
      if (extension != null && !extension.isEmpty()) {
        String mimeType =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        if (mimeType != null && !mimeType.isEmpty()) {
          type = mimeType;
        }
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
    if (isCancelled) {
      return;
    }

    double p = ((double) bytesWritten / (double) contentLength) * 100;
    int progress = (int) Math.round(p);

    Log.d(
        TAG,
        "taskId: "
            + getId().toString()
            + ", bytesWritten: "
            + bytesWritten
            + ", contentLength: "
            + contentLength
            + ", progress: "
            + progress);

    sendUpdateProcessEvent(context, UploadStatus.RUNNING, progress);
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
    if (isCancelled) {
      return;
    }

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
}
