import '../datasources/remote/contact_api_service.dart';
import '../models/contact/contact_models.dart';

/// 联系人仓库
class ContactRepository {
  final ContactApiService _contactApi = ContactApiService();

  /// 获取联系人列表
  Future<List<ContactModel>> getContacts(int userId) async {
    return await _contactApi.getContacts(userId);
  }

  /// 获取按首字母分组的联系人列表
  Future<List<ContactGroup>> getGroupedContacts(int userId) async {
    final contacts = await _contactApi.getContacts(userId);
    return _groupContactsByLetter(contacts);
  }

  /// 添加联系人
  Future<AddContactResponse> addContact({
    required int userId,
    required int contactUserId,
    String? message,
  }) async {
    final request = AddContactRequest(
      userId: userId,
      contactUserId: contactUserId,
      message: message,
    );
    return await _contactApi.addContact(request);
  }

  /// 删除联系人
  Future<void> removeContact(int userId, int contactUserId) async {
    await _contactApi.removeContact(userId, contactUserId);
  }

  /// 检查是否为联系人
  Future<bool> isContact(int userId, int contactUserId) async {
    return await _contactApi.isContact(userId, contactUserId);
  }

  /// 获取待处理的好友申请
  Future<List<ContactRequestModel>> getPendingRequests(int userId) async {
    return await _contactApi.getPendingRequests(userId);
  }

  /// 获取已发送的好友申请
  Future<List<ContactRequestModel>> getSentRequests(int userId) async {
    return await _contactApi.getSentRequests(userId);
  }

  /// 获取待处理申请数量
  Future<int> getPendingRequestCount(int userId) async {
    return await _contactApi.getPendingRequestCount(userId);
  }

  /// 接受好友申请
  Future<ContactModel> acceptRequest(int requestId, int userId) async {
    return await _contactApi.acceptRequest(requestId, userId);
  }

  /// 拒绝好友申请
  Future<void> rejectRequest(int requestId, int userId) async {
    await _contactApi.rejectRequest(requestId, userId);
  }

  /// 获取共同好友
  Future<List<ContactModel>> getMutualContacts(int userId1, int userId2) async {
    return await _contactApi.getMutualContacts(userId1, userId2);
  }

  /// 搜索用户（用于添加联系人）
  Future<List<Map<String, dynamic>>> searchUsers(String query) async {
    return await _contactApi.searchUsers(query);
  }

  /// 获取随机推荐用户
  Future<List<Map<String, dynamic>>> getRandomUsers(int userId, {int limit = 4}) async {
    return await _contactApi.getRandomUsers(userId, limit: limit);
  }

  /// 搜索联系人
  Future<List<ContactModel>> searchContacts(int userId, String query) async {
    final contacts = await _contactApi.getContacts(userId);
    if (query.isEmpty) return contacts;

    final lowerQuery = query.toLowerCase();
    return contacts.where((contact) {
      return contact.displayName.toLowerCase().contains(lowerQuery) ||
          (contact.email?.toLowerCase().contains(lowerQuery) ?? false) ||
          (contact.phone?.contains(query) ?? false);
    }).toList();
  }

  /// 将联系人按首字母分组
  List<ContactGroup> _groupContactsByLetter(List<ContactModel> contacts) {
    // 按首字母排序
    contacts.sort((a, b) {
      final letterA = a.sortLetter;
      final letterB = b.sortLetter;
      // # 放最后
      if (letterA == '#' && letterB != '#') return 1;
      if (letterA != '#' && letterB == '#') return -1;
      return letterA.compareTo(letterB);
    });

    // 分组
    final Map<String, List<ContactModel>> groupMap = {};
    for (final contact in contacts) {
      final letter = contact.sortLetter;
      groupMap.putIfAbsent(letter, () => []);
      groupMap[letter]!.add(contact);
    }

    // 转换为 ContactGroup 列表
    final letters = groupMap.keys.toList()..sort((a, b) {
      if (a == '#') return 1;
      if (b == '#') return -1;
      return a.compareTo(b);
    });

    return letters.map((letter) => ContactGroup(
      letter: letter,
      contacts: groupMap[letter]!,
    )).toList();
  }

  /// 获取所有存在的首字母列表
  Future<List<String>> getAvailableLetters(int userId) async {
    final contacts = await _contactApi.getContacts(userId);
    final letters = contacts.map((c) => c.sortLetter).toSet().toList();
    letters.sort((a, b) {
      if (a == '#') return 1;
      if (b == '#') return -1;
      return a.compareTo(b);
    });
    return letters;
  }
}
