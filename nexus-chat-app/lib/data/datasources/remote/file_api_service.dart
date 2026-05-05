import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../../../core/config/api_config.dart';
import '../../../core/network/dio_client.dart';

/// 文件上传响应模型
class FileUploadResponse {
  final String fileId;
  final String fileUrl;
  final String downloadUrl;
  final String previewUrl;
  final String filename;
  final int size;
  final String? mimeType;

  FileUploadResponse({
    required this.fileId,
    required this.fileUrl,
    required this.downloadUrl,
    required this.previewUrl,
    required this.filename,
    required this.size,
    this.mimeType,
  });

  factory FileUploadResponse.fromJson(Map<String, dynamic> json) {
    return FileUploadResponse(
      fileId: json['fileId'] ?? '',
      fileUrl: json['fileUrl'] ?? '',
      downloadUrl: json['downloadUrl'] ?? '',
      previewUrl: json['previewUrl'] ?? '',
      filename: json['filename'] ?? json['originalName'] ?? '',
      size: json['size'] ?? 0,
      mimeType: json['mimeType'],
    );
  }

  /// 获取完整的预览URL
  String getFullPreviewUrl(String baseUrl) {
    if (previewUrl.startsWith('http')) {
      return previewUrl;
    }
    return '$baseUrl/api$previewUrl';
  }

  /// 获取完整的文件URL
  String getFullFileUrl(String baseUrl) {
    if (fileUrl.startsWith('http')) {
      return fileUrl;
    }
    return '$baseUrl$fileUrl';
  }
}

/// 文件 API 服务
class FileApiService {
  final DioClient _dioClient = DioClient();

  /// 上传单个文件
  Future<FileUploadResponse> uploadFile(
    File file, {
    int? uploaderId,
    void Function(int sent, int total)? onProgress,
  }) async {
    try {
      debugPrint('📁 上传文件: ${file.path}');

      final filename = file.path.split('/').last;
      final formData = FormData.fromMap({
        'file': await MultipartFile.fromFile(
          file.path,
          filename: filename,
        ),
        if (uploaderId != null) 'uploaderId': uploaderId,
      });

      // 使用 dio 实例直接调用以支持 onSendProgress
      final response = await _dioClient.dio.post(
        '${ApiConfig.files}/upload',
        data: formData,
        options: Options(
          contentType: 'multipart/form-data',
        ),
        onSendProgress: onProgress,
      );

      debugPrint('📁 上传成功: ${response.data}');
      return FileUploadResponse.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('📁 上传失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 上传多个文件
  Future<List<FileUploadResponse>> uploadFiles(
    List<File> files, {
    int? uploaderId,
    void Function(int current, int total)? onFileProgress,
  }) async {
    final results = <FileUploadResponse>[];

    for (int i = 0; i < files.length; i++) {
      final response = await uploadFile(
        files[i],
        uploaderId: uploaderId,
      );
      results.add(response);
      onFileProgress?.call(i + 1, files.length);
    }

    return results;
  }

  /// 上传图片并返回URL
  Future<String> uploadImage(String imagePath, {int? uploaderId}) async {
    final file = File(imagePath);
    final response = await uploadFile(file, uploaderId: uploaderId);
    // 根据平台获取基础URL
    final isAndroid = Platform.isAndroid;
    final baseUrl = ApiConfig.getBaseUrl(isAndroid: isAndroid);
    // 返回完整的图片URL
    return response.getFullFileUrl(baseUrl);
  }

  /// 获取文件信息
  Future<FileUploadResponse> getFileInfo(String fileId) async {
    try {
      final response = await _dioClient.get('${ApiConfig.files}/$fileId/info');
      return FileUploadResponse.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('📁 获取文件信息失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 处理错误
  String _handleError(DioException error) {
    if (error.response != null) {
      final data = error.response?.data;
      if (data is Map && data.containsKey('error')) {
        return data['error'];
      }
      switch (error.response?.statusCode) {
        case 400:
          return '文件格式错误或大小超限';
        case 401:
          return '未授权，请重新登录';
        case 413:
          return '文件大小超过限制';
        case 500:
          return '服务器错误';
        default:
          return '上传失败';
      }
    }
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
        return '连接超时，请检查网络';
      case DioExceptionType.sendTimeout:
        return '上传超时，请稍后重试';
      case DioExceptionType.receiveTimeout:
        return '接收超时，请稍后重试';
      case DioExceptionType.connectionError:
        return '网络连接失败，请检查网络';
      default:
        return '网络错误，请稍后重试';
    }
  }
}
