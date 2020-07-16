part of flutter_uploader;

class UploadTaskResponse extends Equatable {
  final String taskId;
  final String response;
  final int statusCode;
  final UploadTaskStatus status;
  final Map<String, String> headers;
  final String tag;

  UploadTaskResponse({
    @required this.taskId,
    this.response,
    this.statusCode,
    this.status,
    this.headers,
    this.tag,
  });

  @override
  bool get stringify => true;

  @override
  List<Object> get props {
    return [
      taskId,
      response,
      statusCode,
      status,
      headers,
      tag,
    ];
  }
}
