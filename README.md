# Flutter Uploader

A plugin for creating and managing upload tasks. Supports iOS and Android.

This plugin is based on [`WorkManager`][1] in Android and [`NSURLSessionUploadTask`][2] in iOS to run upload task in background mode.

This plugin is inspired by [`flutter_downloader`][5]. Thanks to Hung Duy Ha & Flutter Community for great plugins and inspiration.

## iOS integration

- Enable background mode.

<img width="512" src="https://github.com/hnvn/flutter_downloader/blob/master/screenshot/enable_background_mode.png?raw=true"/>

### AppDelegate changes

The plugin supports a background isolate. In order for plugins to work, you need to adjust your AppDelegate as follows:

``` swift
import flutter_uploader

func registerPlugins(registry: FlutterPluginRegistry) {
    GeneratedPluginRegistrant.register(with: registry)
}

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)

    SwiftFlutterUploaderPlugin.registerPlugins = registerPlugins

    // Any further code.
  }
}
```

### Optional configuration:

- **Configure maximum number of connection per host:** the plugin allows 3 simultaneous http connection per host running at a moment by default. You can change this number by adding following codes to your `Info.plist` file.

```xml
<!-- changes this number to configure the maximum number of concurrent tasks -->
<key>FUMaximumConnectionsPerHost</key>
<integer>3</integer>
```

- **Configure maximum number of concurrent upload operation:** the plugin allows 3 simultaneous upload operation running at a moment by default. You can change this number by adding following codes to your `Info.plist` file.

```xml
<!-- changes this number to configure the maximum number of concurrent tasks -->
<key>FUMaximumUploadOperation</key>
<integer>3</integer>
```

- **Configure request timeout:** controls how long (in seconds) a task should wait for additional data to arrive before giving up `Info.plist` file.

```xml
<!-- changes this number to configure the request timeout -->
<key>FUTimeoutInSeconds</key>
<integer>3600</integer>
```

## Android integration

### Optional configuration:

#### Configure maximum number of concurrent tasks

The plugin depends on the `WorkManager` library. The configuration can be done using the instructions at [https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration](https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration).

The example project shows a custom configuration of up to 10 simultaneous uploads.

Two steps are required:

Depend on the appropriate work-runtime in your host App.
``` gradle
implementation "androidx.work:work-runtime:$work_version"
```

Override the default `Application` and implement the `androidx.work.Configuration.Provider` interface:

``` java
@NonNull
@Override
public Configuration getWorkManagerConfiguration() {
  return new Configuration.Builder()
      .setMinimumLoggingLevel(android.util.Log.INFO)
      .setExecutor(Executors.newFixedThreadPool(10))
      .build();
}
```


## Usage

#### Import package:

```dart
import 'package:flutter_uploader/flutter_uploader.dart';
```

#### Configure a background isolate entry point

First, define a top-level function:

``` dart
void backgroundHandler() {
  // Needed so that plugin communication works.
  WidgetsFlutterBinding.ensureInitialized();

  // This uploader instance works within the isolate only.
  FlutterUploader uploader = FlutterUploader();

  // You have now access to:
  uploader.progress.listen((progress) {
    // upload progress
  });
  uploader.result.listen((result) {
    // upload results
  });
}
```

> The backgroundHandler function needs to be either a static function or a top level function to be accessible as a Flutter entry point.

Once you have a function defined, configure it in your main `FlutterUploader` object like so:

``` dart
FlutterUploader().setBackgroundHandler(backgroundHandler);
```

To see how it all works, check out the example.

#### Create new upload task:

**multipart/form-data:**

``` dart
final taskId = await FlutterUploader().enqueue(
  url: "your upload link", //required: url to upload to
  files: [FileItem(path: '/path/to/file', fieldname:"file")], // required: list of files that you want to upload
  method: UploadMethod.POST, // HTTP method  (POST or PUT or PATCH)
  headers: {"apikey": "api_123456", "userkey": "userkey_123456"},
  data: {"name": "john"}, // any data you want to send in upload request
  tag: 'my tag', // custom tag which is returned in result/progress
);
```

**binary uploads:**

``` dart
final taskId = await FlutterUploader().enqueueBinary(
  url: "your upload link", // required: url to upload to
  path: '/path/to/file', // required: list of files that you want to upload
  method: UploadMethod.POST, // HTTP method  (POST or PUT or PATCH)
  headers: {"apikey": "api_123456", "userkey": "userkey_123456"},
  tag: 'my tag', // custom tag which is returned in result/progress
);
```

The plugin will return a `taskId` which is unique for each upload. Hold onto it if you in order to cancel specific uploads.

### listen for upload progress

```dart
final subscription = FlutterUploader().progress.listen((progress) {
  //... code to handle progress
});
```

### listen for upload result

```dart
final subscription = FlutterUploader().result.listen((result) {
    //... code to handle result
}, onError: (ex, stacktrace) {
    // ... code to handle error
});
```

> when tasks are cancelled, it will send on onError handler as exception with status = cancelled

Upload results are persisted by the plugin and will be submitted on each `.listen`. 
It is advised to keep a list of processed uploads in App side and call `clearUploads` on the FlutterUploader plugin once they can be removed.

#### Cancel an upload task:

``` dart
FlutterUploader().cancel(taskId: taskId);
```

#### Cancel all upload tasks:

``` dart
FlutterUploader().cancelAll();
```

#### Clear Uploads

``` dart
FlutterUploader().clearUploads()
```


[1]: https://developer.android.com/topic/libraries/architecture/workmanager
[2]: https://developer.apple.com/documentation/foundation/nsurlsessionuploadtask?language=objc
[3]: https://medium.com/@guerrix/info-plist-localization-ad5daaea732a
[4]: https://developer.android.com/training/basics/supporting-devices/languages
[5]: https://pub.dartlang.org/packages/flutter_downloader
