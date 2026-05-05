import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../../../core/config/api_config.dart';
import '../../../core/network/dio_client.dart';
import '../../models/auth/auth_models.dart';

/// 认证 API 服务
class AuthApiService {
  final DioClient _dioClient = DioClient();

  /// 发送验证码
  Future<bool> sendVerificationCode(String email, {String type = 'REGISTER'}) async {
    try {
      debugPrint('📧 发送验证码请求: email=$email, type=$type');
      final requestData = SendCodeRequest(email: email, type: type).toJson();
      debugPrint('📧 请求数据: $requestData');

      final response = await _dioClient.post(
        ApiConfig.authSendCode,
        data: requestData,
      );

      debugPrint('📧 响应数据: ${response.data}');
      return response.data['success'] == true;
    } on DioException catch (e) {
      debugPrint('📧 发送验证码失败: ${e.message}');
      debugPrint('📧 响应状态码: ${e.response?.statusCode}');
      debugPrint('📧 响应数据: ${e.response?.data}');
      throw _handleError(e);
    }
  }

  /// 验证验证码
  Future<bool> verifyCode(String email, String code, {String type = 'REGISTER'}) async {
    try {
      final response = await _dioClient.post(
        ApiConfig.authVerifyCode,
        data: {
          'email': email,
          'code': code,
          'type': type,
        },
      );
      return response.data['success'] == true;
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// 用户注册
  Future<AuthResponse> register(RegisterRequest request) async {
    try {
      final response = await _dioClient.post(
        ApiConfig.authRegister,
        data: request.toJson(),
      );
      return AuthResponse.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// 用户登录
  Future<AuthResponse> login(LoginRequest request) async {
    try {
      final response = await _dioClient.post(
        ApiConfig.authLogin,
        data: request.toJson(),
      );
      return AuthResponse.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// 用户登出
  Future<void> logout(int userId) async {
    try {
      await _dioClient.post(
        ApiConfig.authLogout,
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// 处理错误
  String _handleError(DioException error) {
    if (error.response != null) {
      final data = error.response?.data;
      if (data is Map && data.containsKey('message')) {
        return data['message'];
      }
      switch (error.response?.statusCode) {
        case 400:
          return '请求参数错误';
        case 401:
          return '未授权，请重新登录';
        case 403:
          return '禁止访问';
        case 404:
          return '资源不存在';
        case 500:
          return '服务器内部错误';
        default:
          return '请求失败';
      }
    }
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
        return '连接超时，请检查网络';
      case DioExceptionType.sendTimeout:
        return '发送超时，请稍后重试';
      case DioExceptionType.receiveTimeout:
        return '接收超时，请稍后重试';
      case DioExceptionType.connectionError:
        return '网络连接失败，请检查网络';
      default:
        return '网络错误，请稍后重试';
    }
  }
}
