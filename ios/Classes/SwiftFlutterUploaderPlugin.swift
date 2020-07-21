import Flutter
import UIKit
import Alamofire

public class SwiftFlutterUploaderPlugin: NSObject, FlutterPlugin {
    private static let channelName = "flutter_uploader";
    private static let progressEventChannelName = "flutter_uploader/events/progress";
    private static let resultEventChannelName = "flutter_uploader/events/result";
    
    static let KEY_TASK_ID = "task_id"
    static let KEY_STATUS = "status"
    static let KEY_PROGRESS = "progress"

    static let KEY_FIELD_NAME = "fieldname"
    static let KEY_PATH = "path"
    static let STEP_UPDATE = 0
    static let DEFAULT_TIMEOUT = 3600.0

    let urlSessionUploader = URLSessionUploader.shared
    
    let channel: FlutterMethodChannel
    let progressEventChannel: FlutterEventChannel
    let progressHandler: SimpleStreamHandler
    
    let resultEventChannel: FlutterEventChannel
    let resultHandler: SimpleStreamHandler
    
    public static var registerPlugins: FlutterPluginRegistrantCallback?
    
    let taskQueue: DispatchQueue

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: SwiftFlutterUploaderPlugin.channelName, binaryMessenger: registrar.messenger())
        let progressEventChannel = FlutterEventChannel(name: SwiftFlutterUploaderPlugin.progressEventChannelName, binaryMessenger: registrar.messenger())
        let resultEventChannel = FlutterEventChannel(name: SwiftFlutterUploaderPlugin.resultEventChannelName, binaryMessenger: registrar.messenger())

        let instance = SwiftFlutterUploaderPlugin(channel, progressEventChannel: progressEventChannel, resultEventChannel: resultEventChannel)
        
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.addApplicationDelegate(instance)
    }

    init (_ channel: FlutterMethodChannel, progressEventChannel: FlutterEventChannel, resultEventChannel: FlutterEventChannel) {
        self.channel = channel
        
        self.progressEventChannel = progressEventChannel
        self.progressHandler = SimpleStreamHandler()
        progressEventChannel.setStreamHandler(progressHandler)
        
        self.resultEventChannel = resultEventChannel
        self.resultHandler = SimpleStreamHandler()
        resultEventChannel.setStreamHandler(resultHandler)
                
        self.taskQueue = DispatchQueue(label: "chillisource.flutter_uploader.dispatch.queue")
        super.init()
        
        urlSessionUploader.addDelegate(self)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "setBackgroundHandler":
            setBackgroundHandler(call, result)
        case "clearUploads":
            result(nil)
        case "enqueue":
            enqueueMethodCall(call, result)
        case "enqueueBinary":
            enqueueBinaryMethodCall(call, result)
        case "cancel":
            cancelMethodCall(call, result)
        case "cancelAll":
            cancelAllMethodCall(call, result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func setBackgroundHandler(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        if let args = call.arguments as? [String:Any] {
            UploaderDefaults.shared.callbackHandle = args["callbackHandle"] as? Int
        }
        
        result(nil)
    }

    private func enqueueMethodCall(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let args = call.arguments as! [String: Any?]
        let urlString = args["url"] as! String
        let method = args["method"] as! String
        let headers = args["headers"] as? [String: Any?]
        let data = args["data"] as? [String: Any?]
        let files = args["files"] as? [Any]
        let tag = args["tag"] as? String

        let validHttpMethods = ["POST", "PUT", "PATCH"]
        let httpMethod = method.uppercased()

        if (!validHttpMethods.contains(httpMethod)) {
            result(FlutterError(code: "invalid_method", message: "Method must be either POST | PUT | PATCH", details: nil))
            return
        }

        if files == nil || files!.count <= 0 {
            result(FlutterError(code: "invalid_files", message: "There are no items to upload", details: nil))
            return
        }

        guard let url = URL(string: urlString) else {
            result(FlutterError(code: "invalid_url", message: "url is not a valid url", details: nil))
            return
        }

        uploadTaskWithURLWithCompletion(url: url, files: files!, method: method, headers: headers, parameters: data, tag: tag, completion: { (task, error) in
            if (error != nil) {
                result(error!)
            } else if let uploadTask = task {
                result(self.urlSessionUploader.identifierForTask(uploadTask))
                
//                self.sendUpdateProgressForTaskId(taskId, inStatus: .enqueue, andProgress: 0, andTag: tag)
            }
        })
    }

    private func enqueueBinaryMethodCall(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let args = call.arguments as! [String: Any?]
        let urlString = args["url"] as! String
        let method = args["method"] as! String
        let headers = args["headers"] as? [String: Any?]
        let file = args["file"] as? [String: Any?]
        let tag = args["tag"] as? String

        let validHttpMethods = ["POST", "PUT", "PATCH"]
        let httpMethod = method.uppercased()

        if (!validHttpMethods.contains(httpMethod)) {
            result(FlutterError(code: "invalid_method", message: "Method must be either POST | PUT | PATCH", details: nil))
            return
        }

        guard let url = URL(string: urlString) else {
            result(FlutterError(code: "invalid_url", message: "url is not a valid url", details: nil))
            return
        }

        guard let f = file else {
            result(FlutterError(code: "invalid_file", message: "file is not a valid path", details: nil))
            return
        }

        let fieldname = f[SwiftFlutterUploaderPlugin.KEY_FIELD_NAME] as! String
        let path = f[SwiftFlutterUploaderPlugin.KEY_PATH] as! String
        let info = UploadFileInfo(fieldname: fieldname, path: path)

        let fileUrl = URL(fileURLWithPath: info.path)

        guard FileManager.default.fileExists(atPath: info.path) else {
            result(FlutterError(code: "invalid_file", message: "file does not exist", details: nil))
            return
        }

        binaryUploadTaskWithURLWithCompletion(url: url, file: fileUrl, method: method, headers: headers, tag: tag, completion: { (task, error) in
            if (error != nil) {
                result(error!)
            } else if let uploadTask = task {
                result(self.urlSessionUploader.identifierForTask(uploadTask))
            }
        })
    }

    private func cancelMethodCall(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let args = call.arguments as! [String: Any?]
        let taskId = args[SwiftFlutterUploaderPlugin.KEY_TASK_ID] as! String
        
        urlSessionUploader.cancelWithTaskId(taskId)

        result(nil)
    }

    private func cancelAllMethodCall(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        urlSessionUploader.cancelAllTasks() 

        result(nil)
    }

    private func binaryUploadTaskWithURLWithCompletion(url: URL,
                                                       file: URL,
                                                       method: String,
                                                       headers: [String: Any?]?,
                                                       tag: String?,
                                                       completion completionHandler:@escaping (URLSessionUploadTask?, FlutterError?) -> Void) {
        let request = NSMutableURLRequest(url: url)
        request.httpMethod = method
        request.setValue("*/*", forHTTPHeaderField: "Accept")

        headers?.forEach { (key, value) in
            if let v = value as? String {
                request.setValue(v, forHTTPHeaderField: key)
            }
        }

        completionHandler(self.urlSessionUploader.enqueueUploadTask(request as URLRequest, path: file.path), nil)
    }

    private func uploadTaskWithURLWithCompletion(url: URL, files: [Any],
                                                 method: String,
                                                 headers: [String: Any?]?,
                                                 parameters data: [String: Any?]?,
                                                 tag: String?,
                                                 completion completionHandler:@escaping (URLSessionUploadTask?, FlutterError?) -> Void) {

            var flutterError: FlutterError?
            let fm = FileManager.default
            var fileCount: Int = 0
            let formData = MultipartFormData()
            let tempDirectory = NSURL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)

            if(data != nil) {
                data?.forEach({ (key, value) in
                    if let v = value as? String {
                        formData.append(v.data(using: .utf8)!, withName: key)
                    }
                })
            }

            for file in files {
                let f = file as! [String: Any]
                
                let fieldname = f[SwiftFlutterUploaderPlugin.KEY_FIELD_NAME] as! String
                let path = f[SwiftFlutterUploaderPlugin.KEY_PATH] as! String

                var isDir: ObjCBool = false

                let fm = FileManager.default
                if fm.fileExists(atPath: path, isDirectory: &isDir) {
                    if !isDir.boolValue {
                        let fileInfo = UploadFileInfo(fieldname: fieldname, path: path)
                        let filePath = URL(fileURLWithPath: fileInfo.path)
                        formData.append(filePath, withName: fileInfo.fieldname, fileName: filePath.lastPathComponent, mimeType: fileInfo.mimeType)
                        fileCount += 1
                    } else {
                        flutterError = FlutterError(code: "io_error", message: "path \(path) is a directory. please provide valid file path", details: nil)
                    }
                } else {
                    flutterError = FlutterError(code: "io_error", message: "file at path \(path) doesn't exists", details: nil)
                }
            }

            if fileCount <= 0 {
                completionHandler(nil, flutterError)
            } else {
                let requestId = UUID().uuidString.replacingOccurrences(of: "-", with: "_")
                let requestFile = "\(requestId).req"
                let tempPath = tempDirectory.appendingPathComponent(requestFile, isDirectory: false)

                if fm.fileExists(atPath: tempPath!.path) {
                    do {
                        try fm.removeItem(at: tempPath!)
                    } catch {
                        completionHandler(nil, FlutterError(code: "io_error", message: "failed to delete file \(requestFile)", details: nil))
                        return
                    }
                }

                let path = tempPath!.path
                do {
                    let requestfileURL = URL(fileURLWithPath: path)
                    try formData.writeEncodedData(to: requestfileURL)
                } catch {
                    completionHandler(nil, FlutterError(code: "io_error", message: "failed to write request \(requestFile)", details: nil))
                    return
                }

                self.makeRequest(path, url, method, headers, formData.contentType, formData.contentLength, completion: {
                    (task, error) in
                    completionHandler(task, error)
                })
            }
    }

    private func makeRequest(_ path: String, _ url: URL, _ method: String, _ headers: [String: Any?]?, _ contentType: String, _ contentLength: UInt64, completion completionHandler: (URLSessionUploadTask?, FlutterError?) -> Void) {

        let request = NSMutableURLRequest(url: url)
        request.httpMethod = method
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.setValue("\(contentType)", forHTTPHeaderField: "Content-Type")
        request.setValue("\(contentLength)", forHTTPHeaderField: "Content-Length")

        if headers != nil {
            headers!.forEach { (key, value) in
                if let v = value as? String {
                    request.setValue(v, forHTTPHeaderField: key)
                }
            }
        }

        let fm = FileManager.default

        if !fm.fileExists(atPath: path) {
            completionHandler(nil, FlutterError(code: "io_error", message: "request file can not be found in path \(path)", details: nil))
            return
        }

        completionHandler(urlSessionUploader.enqueueUploadTask(request as URLRequest, path: path), nil)
    }
}

/// UIApplicationDelegate
extension SwiftFlutterUploaderPlugin {
    public func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) -> Bool {
        NSLog("ApplicationHandleEventsForBackgroundURLSession: \(identifier)")
        if identifier == URLSessionUploader.KEY_BACKGROUND_SESSION_IDENTIFIER {
            urlSessionUploader.backgroundTransferCompletionHander = completionHandler
        }
        
        return true
    }

    public func applicationWillTerminate(_ application: UIApplication) {
//        runningTaskById.removeAll()
//        uploadedData.removeAll()
//        queue.cancelAllOperations()
    }
}

extension SwiftFlutterUploaderPlugin : UploaderDelegate {
    func uploadProgressed(taskId: String, inStatus: UploadTaskStatus, progress: Int) {
        progressHandler.add([
            SwiftFlutterUploaderPlugin.KEY_TASK_ID: taskId,
            SwiftFlutterUploaderPlugin.KEY_STATUS: inStatus.rawValue,
            SwiftFlutterUploaderPlugin.KEY_PROGRESS: progress,
//            "tag": (tag ?? NSNull()) as Any
        ])
    }
    
    func uploadCompleted(taskId: String, message: String, statusCode: Int, headers: [String : Any]) {
        resultHandler.add([
            SwiftFlutterUploaderPlugin.KEY_TASK_ID: taskId,
            SwiftFlutterUploaderPlugin.KEY_STATUS: UploadTaskStatus.completed.rawValue,
            "message": message,
            "statusCode": statusCode,
            "headers": headers,
//            "tag": tag ?? NSNull()
        ])

    }
    
    func uploadFailed(taskId: String, inStatus: UploadTaskStatus, statusCode: Int, errorCode: String, errorMessage: String, errorStackTrace: [String]) {
        resultHandler.add([
            SwiftFlutterUploaderPlugin.KEY_TASK_ID: taskId,
            SwiftFlutterUploaderPlugin.KEY_STATUS: inStatus.rawValue,
            "statusCode": NSNumber(integerLiteral: statusCode),
            "code": errorCode,
            "message": errorMessage,
            "details": errorStackTrace
        ])
    }
    
    
}
