import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../config/api_config.dart';
import '../storage/secure_storage.dart';

/// Dio HTTP 客户端
class DioClient {
  static DioClient? _instance;
  late final Dio _dio;
  final SecureStorageService _secureStorage = SecureStorageService();

  DioClient._() {
    _dio = Dio(_baseOptions);
    _setupInterceptors();
  }

  factory DioClient() {
    _instance ??= DioClient._();
    return _instance!;
  }

  Dio get dio => _dio;

  /// 基础配置
  BaseOptions get _baseOptions {
    final isAndroid = !kIsWeb && Platform.isAndroid;
    return BaseOptions(
      baseUrl: ApiConfig.getBaseUrl(isAndroid: isAndroid),
      connectTimeout: const Duration(milliseconds: ApiConfig.connectTimeout),
      receiveTimeout: const Duration(milliseconds: ApiConfig.receiveTimeout),
      sendTimeout: const Duration(milliseconds: ApiConfig.sendTimeout),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    );
  }

  /// 设置拦截器
  void _setupInterceptors() {
    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: _onRequest,
        onResponse: _onResponse,
        onError: _onError,
      ),
    );

    // 日志拦截器 (仅开发环境)
    if (!ApiConfig.isProduction) {
      _dio.interceptors.add(
        LogInterceptor(
          requestBody: true,
          responseBody: true,
          logPrint: (obj) => debugPrint(obj.toString()),
        ),
      );
    }
  }

  /// 请求拦截
  Future<void> _onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    // 添加 Token
    final token = await _secureStorage.getToken();
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  /// 响应拦截
  Future<void> _onResponse(
    Response response,
    ResponseInterceptorHandler handler,
  ) async {
    // 请求成功时更新最后活跃时间
    await _secureStorage.updateLastActiveTime();
    handler.next(response);
  }

  /// 错误拦截
  Future<void> _onError(
    DioException error,
    ErrorInterceptorHandler handler,
  ) async {
    // 处理 401 未授权
    if (error.response?.statusCode == 401) {
      // 软登出：保留账号记忆，清除登录状态
      await _secureStorage.softLogout();
      // 注意：页面跳转由各页面自行处理（检测到未登录状态后跳转）
    }
    handler.next(error);
  }

  /// GET 请求
  Future<Response<T>> get<T>(
    String path, {
    Map<String, dynamic>? queryParameters,
    Options? options,
  }) {
    return _dio.get<T>(
      path,
      queryParameters: queryParameters,
      options: options,
    );
  }

  /// POST 请求
  Future<Response<T>> post<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    Options? options,
  }) {
    return _dio.post<T>(
      path,
      data: data,
      queryParameters: queryParameters,
      options: options,
    );
  }

  /// PUT 请求
  Future<Response<T>> put<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    Options? options,
  }) {
    return _dio.put<T>(
      path,
      data: data,
      queryParameters: queryParameters,
      options: options,
    );
  }

  /// DELETE 请求
  Future<Response<T>> delete<T>(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    Options? options,
  }) {
    return _dio.delete<T>(
      path,
      data: data,
      queryParameters: queryParameters,
      options: options,
    );
  }
}
