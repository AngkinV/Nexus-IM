# Nexus Chat 前端功能设计总结文档

## 📋 概述

本次前端更新为 Nexus Chat 实现了五个核心功能的界面设计：

1. **群聊功能** - 创建和管理群组聊天
2. **添加联系人功能** - 搜索和添加好友
3. **个人资料编辑功能** - 完善个人信息和隐私设置
4. **文件分享功能** - 上传和分享图片、视频、音频、文档
5. **视频/语音通话功能** - 基于WebRTC的实时音视频通话

---

## 🎯 功能五：视频/语音通话 (Video/Voice Call)

### 5.1 前端组件

#### CallView.vue
- **位置**: `/src/components/call/CallView.vue`
- **功能**: 通话进行中界面
  - 远程视频全屏显示
  - 本地视频 PiP 小窗口（右下角）
  - 通话时长计时器
  - 连接状态显示
  - 控制按钮：静音、关闭视频、切换摄像头、扬声器、挂断
  - 音频通话时显示头像和声波动画
  - 远程视频关闭时的占位图

#### IncomingCallModal.vue
- **位置**: `/src/components/call/IncomingCallModal.vue`
- **功能**: 来电弹窗
  - 来电者头像（带脉冲动画）
  - 来电者姓名
  - 通话类型显示（视频/语音）
  - 接听按钮（绿色）
  - 拒绝按钮（红色）
  - 铃声播放

#### OutgoingCallModal.vue
- **位置**: `/src/components/call/OutgoingCallModal.vue`
- **功能**: 去电弹窗
  - 被叫者头像（带涟漪动画）
  - 被叫者姓名
  - 动态状态文本（呼叫中/连接中）
  - 取消按钮
  - 回铃音播放

#### CallEndModal.vue
- **位置**: `/src/components/call/CallEndModal.vue`
- **功能**: 通话结束界面
  - 通话结果显示（已完成/已拒绝/忙线/超时等）
  - 通话时长统计

### 5.2 服务层

#### webrtc.js
- **位置**: `/src/services/webrtc.js`
- **功能**: WebRTC 核心服务
  - ICE 服务器配置（STUN/TURN）
  - 本地媒体流获取（音频/视频）
  - RTCPeerConnection 创建和管理
  - SDP Offer/Answer 处理
  - ICE Candidate 管理（含队列机制）
  - 媒体控制（静音、关闭视频、切换摄像头）
  - 连接统计信息获取

#### websocket.js (通话信令相关)
- **位置**: `/src/services/websocket.js`
- **功能**: WebSocket 信令通道
  - 通话信令发送和接收
  - 信令类型：CALL_INVITE, CALL_ACCEPT, CALL_REJECT, CALL_CANCEL, CALL_BUSY, CALL_TIMEOUT, CALL_END, CALL_OFFER, CALL_ANSWER, CALL_ICE_CANDIDATE, CALL_MUTE, CALL_VIDEO_TOGGLE

### 5.3 状态管理

#### call.js (Pinia Store)
- **位置**: `/src/stores/call.js`
- **功能**: 通话状态管理
  - 通话状态机：IDLE → CALLING/RINGING → CONNECTING → CONNECTED → ENDED
  - 本地/远程媒体流管理
  - 通话计时器
  - 铃声/回铃音控制
  - 信令处理和分发

### 5.4 数据结构

```javascript
// 通话状态枚举
export const CallStatus = {
    IDLE: 'idle',           // 空闲
    RINGING: 'ringing',     // 来电铃声
    CALLING: 'calling',     // 去电等待
    CONNECTING: 'connecting', // WebRTC连接中
    CONNECTED: 'connected', // 通话中
    ENDED: 'ended'          // 已结束
}

// 通话结束原因
export const CallEndReason = {
    COMPLETED: 'completed', // 正常挂断
    REJECTED: 'rejected',   // 被拒绝
    CANCELLED: 'cancelled', // 被取消
    BUSY: 'busy',           // 忙线
    TIMEOUT: 'timeout',     // 超时
    FAILED: 'failed',       // 连接失败
    MISSED: 'missed'        // 未接
}

// 当前通话对象结构
{
    callId: String,          // 通话UUID
    callType: 'audio' | 'video', // 通话类型
    direction: 'incoming' | 'outgoing', // 通话方向
    remoteUser: {
        id: Number,          // 对方用户ID
        name: String,        // 对方昵称
        avatar: String       // 对方头像
    },
    startTime: Date,         // 通话开始时间
    remoteIsMuted: Boolean,  // 对方是否静音
    remoteVideoEnabled: Boolean // 对方是否开启视频
}
```

### 5.5 WebSocket 信令协议

```javascript
// 信令消息结构
{
    type: 'CALL_INVITE' | 'CALL_ACCEPT' | ...,
    payload: {
        callId: String,
        callerId: Number,    // 原始主叫方ID
        calleeId: Number,    // 原始被叫方ID
        callType: 'audio' | 'video',
        sdp: RTCSessionDescription,  // SDP offer/answer
        candidate: RTCIceCandidate   // ICE candidate
    }
}

// 信令流程
主叫方                          服务器                          被叫方
   |                              |                              |
   |-- CALL_INVITE -------------->|-- CALL_INVITE -------------->|
   |                              |                              |
   |<-- CALL_ACCEPT --------------|<-- CALL_ACCEPT --------------|
   |                              |                              |
   |-- CALL_OFFER --------------->|-- CALL_OFFER --------------->|
   |                              |                              |
   |<-- CALL_ANSWER --------------|<-- CALL_ANSWER --------------|
   |                              |                              |
   |<-- CALL_ICE_CANDIDATE ------>|<-- CALL_ICE_CANDIDATE ------>|
   |                              |                              |
   |====== 通话进行中 ============|====== 通话进行中 ============|
   |                              |                              |
   |-- CALL_END ----------------->|-- CALL_END ----------------->|
```

### 5.6 ICE 服务器配置

```javascript
{
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        { urls: 'stun:stun2.l.google.com:19302' },
        { urls: 'stun:stun3.l.google.com:19302' },
        { urls: 'stun:stun4.l.google.com:19302' },
        // 生产环境需要添加 TURN 服务器:
        // {
        //     urls: 'turn:your-turn-server.com:3478',
        //     username: 'username',
        //     credential: 'password'
        // }
    ],
    iceCandidatePoolSize: 10
}
```

### 5.7 Bug 修复记录 (2026-02-07)

#### 问题描述
1. 拨号者电话被接通后一直显示"连接中"，只能看到自己的摄像头画面
2. 接听者也只能看到自己的摄像头画面
3. 视频通话中语音也获取不到

#### 根本原因分析

| 问题 | 原因 | 影响 |
|------|------|------|
| CALL_ANSWER 信令参数错误 | 被叫方发送 CALL_ANSWER 时，callerId 和 calleeId 参数传反了 | 后端把 answer 发回给了被叫方自己，主叫方收不到 |
| ICE Candidate 字段错误 | 前端使用 `callerId/calleeId`，后端期望 `userId/remoteUserId` | ICE candidate 可能发送到错误的目标 |
| Vue 响应式更新问题 | remoteStream 对象不变但内部 track 改变时，Vue 无法检测到 | 视频元素不会自动更新 |
| ontrack 事件处理不当 | 手动创建 MediaStream 而不是使用 `event.streams[0]` | 流可能不完整 |

#### 修复内容

**文件: `/src/services/webrtc.js`**
```javascript
// 修复前
this.peerConnection.ontrack = (event) => {
    if (!this.remoteStream) {
        this.remoteStream = new MediaStream()
    }
    this.remoteStream.addTrack(event.track)
    ...
}

// 修复后 - 使用 event.streams[0]
this.peerConnection.ontrack = (event) => {
    if (event.streams && event.streams[0]) {
        this.remoteStream = event.streams[0]
    } else {
        // Fallback
        if (!this.remoteStream) {
            this.remoteStream = new MediaStream()
        }
        this.remoteStream.addTrack(event.track)
    }
    ...
}
```

**文件: `/src/stores/call.js` - handleOffer 函数**
```javascript
// 修复前 - callerId/calleeId 传反了
websocket.sendCallAnswer(
    currentCall.value.callId,
    userStore.currentUser.id,        // 错误：当前用户是被叫方
    currentCall.value.remoteUser.id, // 错误：远程用户是主叫方
    answer
)

// 修复后 - 根据通话方向正确设置
const isIncoming = currentCall.value.direction === 'incoming'
const originalCallerId = isIncoming ? currentCall.value.remoteUser.id : userStore.currentUser.id
const originalCalleeId = isIncoming ? userStore.currentUser.id : currentCall.value.remoteUser.id

websocket.sendCallAnswer(
    currentCall.value.callId,
    originalCallerId,  // 正确：原始主叫方
    originalCalleeId,  // 正确：原始被叫方
    answer
)
```

**文件: `/src/services/websocket.js` - sendIceCandidate 函数**
```javascript
// 修复前 - 使用 callerId/calleeId
sendIceCandidate(callId, callerId, calleeId, candidate) {
    return this.sendCallSignal('CALL_ICE_CANDIDATE', {
        callId, callerId, calleeId, candidate
    })
}

// 修复后 - 使用 userId/remoteUserId
sendIceCandidate(callId, userId, remoteUserId, candidate) {
    return this.sendCallSignal('CALL_ICE_CANDIDATE', {
        callId, userId, remoteUserId, candidate
    })
}
```

**文件: `/src/components/call/CallView.vue`**
- 移除 `:srcObject` 模板绑定
- 使用 watcher + nextTick 手动设置 srcObject
- 添加隐藏的 audio 元素用于音频播放
- 强制 Vue 响应式更新（先设 null 再设新值）

### 5.8 后端 API 需求

后端 WebSocket 控制器需要处理以下信令类型：

| 信令类型 | 方向 | 描述 |
|----------|------|------|
| CALL_INVITE | 主叫 → 被叫 | 发起呼叫 |
| CALL_ACCEPT | 被叫 → 主叫 | 接受呼叫 |
| CALL_REJECT | 被叫 → 主叫 | 拒绝呼叫 |
| CALL_CANCEL | 主叫 → 被叫 | 取消呼叫 |
| CALL_BUSY | 被叫 → 主叫 | 忙线 |
| CALL_TIMEOUT | 双向 | 超时 |
| CALL_END | 双向 | 结束通话 |
| CALL_OFFER | 主叫 → 被叫 | SDP Offer |
| CALL_ANSWER | 被叫 → 主叫 | SDP Answer |
| CALL_ICE_CANDIDATE | 双向 | ICE Candidate |
| CALL_MUTE | 双向 | 静音状态 |
| CALL_VIDEO_TOGGLE | 双向 | 视频开关状态 |

---

## 🎯 功能四：文件分享 (File Sharing)

### 4.1 前端组件

#### FileUpload.vue
- **位置**: `/src/components/chat/FileUpload.vue`
- **功能**: 文件选择和上传组件
  - 拖拽上传支持
  - 文件类型预览图标
  - 实时上传进度条（含上传速度）
  - 大文件自动分片上传（>5MB）
  - 取消上传功能
  - 文件大小限制提示（100MB）

#### MessageList.vue (增强)
- **位置**: `/src/components/chat/MessageList.vue`
- **功能**: 消息列表支持多媒体消息
  - **图片消息**: 缩略图展示，点击放大预览
  - **视频消息**: 内嵌播放器，支持下载
  - **音频消息**: 内嵌音频播放器
  - **文件消息**: 文件图标、名称、大小，点击下载

#### MessageInput.vue (增强)
- **位置**: `/src/components/chat/MessageInput.vue`
- **功能**: 消息输入框支持文件发送
  - 附件按钮触发文件选择
  - 文件上传后自动发送消息
  - 消息类型自动识别（IMAGE/VIDEO/AUDIO/FILE）

### 4.2 数据结构

```javascript
// 文件消息对象结构
{
  id: Number,              // 消息ID
  chatId: Number,          // 聊天ID
  senderId: Number,        // 发送者ID
  type: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'FILE',  // 消息类型
  content: String,         // 消息内容/文件名
  fileId: String,          // 文件UUID
  fileName: String,        // 原始文件名
  fileSize: Number,        // 文件大小(字节)
  mimeType: String,        // MIME类型
  fileUrl: String,         // 静态文件URL
  downloadUrl: String,     // 下载接口URL
  previewUrl: String,      // 预览接口URL
  timestamp: Date,         // 发送时间
  read: Boolean            // 是否已读
}

// 文件上传响应结构
{
  fileId: String,          // 文件UUID
  fileUrl: String,         // 静态文件路径
  downloadUrl: String,     // 下载URL
  previewUrl: String,      // 预览URL
  filename: String,        // 文件名
  originalName: String,    // 原始文件名
  size: Number,            // 文件大小
  mimeType: String,        // MIME类型
  expiresAt: Date          // 过期时间
}
```

### 4.3 后端API

| 方法 | 端点 | 描述 | 请求参数 | 响应 |
|------|------|------|----------|------|
| POST | `/api/files/upload` | 单文件上传 | `MultipartFile file, Long uploaderId` | 文件信息 |
| POST | `/api/files/upload/chunk` | 分片上传 | `分片参数` | 分片状态 |
| GET | `/api/files/{fileId}/info` | 获取文件信息 | - | 文件信息 |
| GET | `/api/files/download/{fileId}` | 下载文件 | - | 文件流 |
| GET | `/api/files/preview/{fileId}` | 预览文件 | - | 文件流(inline) |

### 4.4 功能特性

- **秒传**: 基于MD5检测重复文件，秒传已存在的文件
- **分片上传**: 大于5MB的文件自动分片，支持大文件上传
- **进度显示**: 实时显示上传进度和速度
- **自动清理**: 文件30天后自动过期删除
- **类型识别**: 自动识别图片/视频/音频/文档类型

---

## 🎯 功能一：群聊 (Group Chat)

### 1.1 前端组件

#### CreateGroupModal.vue
- **位置**: `/src/components/chat/CreateGroupModal.vue`
- **功能**: 两步式群组创建向导
  - **步骤1**: 群组基本信息
    - 群组头像上传 (支持本地图片)
    - 群组名称 (必填，最大50字符)
    - 群组简介 (可选，最大200字符)
    - 私密群组开关 (私密/公开)
  - **步骤2**: 添加成员
    - 成员搜索功能
    - 已选成员预览标签
    - 可用成员列表 (带在线状态)
    - 成员数量统计

#### GroupList.vue
- **位置**: `/src/components/chat/GroupList.vue`
- **功能**: 群组列表展示
  - 群组头像 (支持文字首字母fallback)
  - 私密群组标识
  - 成员数量显示
  - 未读消息角标
  - 最后消息预览和时间

### 1.2 数据结构

```javascript
// 群组对象结构
{
  id: Number,              // 群组ID
  name: String,            // 群组名称
  description: String,     // 群组简介
  avatar: String,          // 群组头像URL
  type: 'GROUP',           // 聊天类型
  isPrivate: Boolean,      // 是否私密群组
  members: Array<Number>,  // 成员ID列表
  memberCount: Number,     // 成员数量
  lastMessage: String,     // 最后一条消息
  lastMessageTime: Date,   // 最后消息时间
  unreadCount: Number,     // 未读消息数
  createdAt: Date,         // 创建时间
  creatorId: Number        // 创建者ID
}
```

### 1.3 后端API需求

| 方法 | 端点 | 描述 | 请求参数 | 响应 |
|------|------|------|----------|------|
| POST | `/api/groups` | 创建群组 | `{ name, description, avatar, isPrivate, memberIds[] }` | `Group对象` |
| GET | `/api/groups/{id}` | 获取群组详情 | - | `Group对象` |
| PUT | `/api/groups/{id}` | 更新群组信息 | `{ name?, description?, avatar?, isPrivate? }` | `Group对象` |
| DELETE | `/api/groups/{id}` | 删除/解散群组 | - | - |
| POST | `/api/groups/{id}/members` | 添加成员 | `{ userIds[] }` | - |
| DELETE | `/api/groups/{id}/members/{userId}` | 移除成员 | - | - |
| POST | `/api/groups/{id}/leave` | 退出群组 | - | - |
| GET | `/api/groups/{id}/members` | 获取成员列表 | - | `User[]` |
| GET | `/api/users/{userId}/groups` | 获取用户加入的群组 | - | `Group[]` |

---

## 🎯 功能二：添加联系人 (Add Contact)

### 2.1 前端组件

#### AddContactModal.vue
- **位置**: `/src/components/contact/AddContactModal.vue`
- **功能**: 搜索和添加联系人
  - 多条件搜索 (用户名/昵称/邮箱)
  - 搜索结果展示 (头像、昵称、用户名、邮箱)
  - 添加按钮和已添加状态
  - 搜索中加载动画
  - 未找到用户提示
  - 直接输入用户ID添加
  - 推荐联系人列表

#### ContactList.vue
- **位置**: `/src/components/contact/ContactList.vue`
- **功能**: 联系人列表展示
  - 在线/离线分组显示
  - 在线状态指示器
  - 最后在线时间 (相对时间)
  - 快捷聊天按钮

### 2.2 数据结构

```javascript
// 联系人对象结构
{
  id: Number,              // 用户ID
  username: String,        // 用户名
  nickname: String,        // 昵称
  avatar: String,          // 头像URL
  email: String,           // 邮箱 (可选显示)
  phone: String,           // 手机号 (可选显示)
  isOnline: Boolean,       // 在线状态
  lastSeen: Date,          // 最后在线时间
  addedAt: Date            // 添加时间
}
```

### 2.3 后端API需求

| 方法 | 端点 | 描述 | 请求参数 | 响应 |
|------|------|------|----------|------|
| GET | `/api/users/search` | 搜索用户 | `?query=xxx` | `User[]` |
| POST | `/api/contacts` | 添加联系人 | `{ userId, contactUserId }` | `Contact对象` |
| DELETE | `/api/contacts` | 删除联系人 | `{ userId, contactUserId }` | - |
| GET | `/api/contacts/user/{userId}` | 获取联系人列表 | - | `Contact[]` |
| GET | `/api/users/recommended` | 获取推荐联系人 | - | `User[]` |
| GET | `/api/users/{id}` | 通过ID获取用户 | - | `User对象` |

---

## 🎯 功能三：个人资料编辑 (Profile Editing)

### 3.1 前端组件

#### EditProfileModal.vue
- **位置**: `/src/components/common/EditProfileModal.vue`
- **功能**: 编辑个人资料模态框
  - 渐变色头部设计
  - 头像上传/删除功能
  - 表单字段:
    - 昵称 (必填，2-30字符)
    - 个人简介 (选填，最大150字符)
    - 用户名 (只读，不可更改)
    - 邮箱 (可编辑)
    - 手机号 (可编辑)
  - 隐私设置:
    - 显示在线状态开关
    - 显示最后在线时间开关
    - 公开邮箱开关

#### Profile.vue
- **位置**: `/src/views/Profile.vue`
- **功能**: 个人资料页面
  - 渐变头部背景
  - 大头像展示 (带在线状态)
  - 用户基本信息卡片
  - 统计数据 (联系人数、群组数、消息数)
  - 操作菜单 (设置、编辑资料、隐私设置、退出登录)

### 3.2 数据结构

```javascript
// 用户资料对象结构
{
  id: Number,                // 用户ID
  username: String,          // 用户名 (不可更改)
  nickname: String,          // 昵称
  email: String,             // 邮箱
  phone: String,             // 手机号
  avatar: String,            // 头像URL
  bio: String,               // 个人简介
  showOnlineStatus: Boolean, // 是否显示在线状态
  showLastSeen: Boolean,     // 是否显示最后在线时间
  showEmail: Boolean,        // 是否公开邮箱
  createdAt: Date,           // 注册时间
  isOnline: Boolean          // 在线状态
}
```

### 3.3 后端API需求

| 方法 | 端点 | 描述 | 请求参数 | 响应 |
|------|------|------|----------|------|
| GET | `/api/users/{id}/profile` | 获取用户资料 | - | `UserProfile对象` |
| PUT | `/api/users/{id}/profile` | 更新用户资料 | `{ nickname, bio, email, phone }` | `UserProfile对象` |
| POST | `/api/users/{id}/avatar` | 上传头像 | `FormData(file)` | `{ avatarUrl }` |
| DELETE | `/api/users/{id}/avatar` | 删除头像 | - | - |
| PUT | `/api/users/{id}/privacy` | 更新隐私设置 | `{ showOnlineStatus, showLastSeen, showEmail }` | - |
| GET | `/api/users/{id}/stats` | 获取用户统计 | - | `{ contactCount, groupCount, messageCount }` |

---

## 📁 新增/修改的文件列表

### 新增文件
| 文件路径 | 描述 |
|----------|------|
| `/src/views/Profile.vue` | 个人资料页面 |
| `/src/components/contact/ContactList.vue` | 联系人列表组件 |
| `/src/components/chat/GroupList.vue` | 群组列表组件 |
| `/src/components/chat/FileUpload.vue` | 文件上传组件 |
| `/src/components/call/CallView.vue` | 通话进行中界面 |
| `/src/components/call/IncomingCallModal.vue` | 来电弹窗组件 |
| `/src/components/call/OutgoingCallModal.vue` | 去电弹窗组件 |
| `/src/components/call/CallEndModal.vue` | 通话结束界面 |
| `/src/services/webrtc.js` | WebRTC 核心服务 |
| `/src/stores/call.js` | 通话状态管理 |

### 修改文件
| 文件路径 | 修改内容 |
|----------|----------|
| `/src/components/chat/CreateGroupModal.vue` | 重构为两步向导，增加群组描述和私密设置 |
| `/src/components/contact/AddContactModal.vue` | 增强搜索功能，添加推荐联系人 |
| `/src/components/common/EditProfileModal.vue` | 增加隐私设置，优化UI |
| `/src/components/layout/LeftPanel.vue` | 添加Tab切换（聊天/联系人/群组），可点击头像进入资料页 |
| `/src/components/chat/MessageList.vue` | 支持IMAGE/VIDEO/AUDIO/FILE消息类型渲染 |
| `/src/components/chat/MessageInput.vue` | 集成文件上传，支持附件发送 |
| `/src/components/chat/MiddlePanel.vue` | 处理文件消息发送逻辑 |
| `/src/services/api.js` | 新增fileAPI（上传、分片上传、文件信息查询） |
| `/src/services/websocket.js` | WebSocket心跳机制、断线重连、文件消息字段解析、**通话信令支持** |
| `/src/stores/chat.js` | 增加群组管理方法、clearSubscribedChats |
| `/src/stores/contact.js` | 增加联系人过滤和搜索方法 |
| `/src/stores/user.js` | 增加资料更新和隐私设置方法 |
| `/src/router/index.js` | 添加 `/profile` 路由 |
| `/src/locales/zh.json` | 添加新功能中文翻译 |
| `/src/locales/en.json` | 添加新功能英文翻译 |

---

## 🗄️ 数据库表结构建议

### 1. file_uploads 表 (新增)
```sql
CREATE TABLE file_uploads (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id VARCHAR(36) NOT NULL UNIQUE,
    filename VARCHAR(255),
    original_name VARCHAR(255),
    stored_name VARCHAR(255),
    file_size BIGINT,
    mime_type VARCHAR(100),
    md5_hash VARCHAR(32),
    uploader_id BIGINT,
    message_id BIGINT,
    file_path VARCHAR(500),
    chunk_count INT DEFAULT 1,
    upload_complete BOOLEAN DEFAULT FALSE,
    expires_at DATETIME,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id),
    INDEX idx_md5_hash (md5_hash),
    INDEX idx_uploader_id (uploader_id),
    INDEX idx_expires_at (expires_at)
);
```

### 2. user_privacy_settings 表 (新增)
```sql
CREATE TABLE user_privacy_settings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    show_online_status BOOLEAN DEFAULT TRUE,
    show_last_seen BOOLEAN DEFAULT TRUE,
    show_email BOOLEAN DEFAULT FALSE,
    show_phone BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

### 2. users 表 (修改建议)
```sql
-- 添加 bio 字段
ALTER TABLE users ADD COLUMN bio VARCHAR(150) DEFAULT NULL;

-- 添加 is_online 字段
ALTER TABLE users ADD COLUMN is_online BOOLEAN DEFAULT FALSE;

-- 添加 last_seen 字段
ALTER TABLE users ADD COLUMN last_seen TIMESTAMP DEFAULT NULL;
```

### 3. chat_groups 表 (如果不存在)
```sql
CREATE TABLE chat_groups (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(200),
    avatar_url VARCHAR(255),
    is_private BOOLEAN DEFAULT FALSE,
    creator_id BIGINT NOT NULL,
    member_count INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_id) REFERENCES users(id)
);
```

### 4. group_members 表 (如果不存在)
```sql
CREATE TABLE group_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role ENUM('admin', 'member') DEFAULT 'member',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_group_member (group_id, user_id)
);
```

---

## 🔌 WebSocket事件需求

### 群聊相关
```javascript
// 客户端 -> 服务器
socket.emit('group:create', { name, description, isPrivate, memberIds });
socket.emit('group:join', { groupId });
socket.emit('group:leave', { groupId });
socket.emit('group:message', { groupId, content, type });

// 服务器 -> 客户端
socket.on('group:created', (group) => { ... });
socket.on('group:member-joined', ({ groupId, user }) => { ... });
socket.on('group:member-left', ({ groupId, userId }) => { ... });
socket.on('group:message', (message) => { ... });
```

### 联系人相关
```javascript
// 客户端 -> 服务器
socket.emit('contact:add', { contactUserId });
socket.emit('contact:remove', { contactUserId });

// 服务器 -> 客户端
socket.on('contact:added', (contact) => { ... });
socket.on('contact:removed', ({ contactId }) => { ... });
socket.on('contact:status-changed', ({ userId, isOnline }) => { ... });
```

### 用户状态相关
```javascript
// 服务器 -> 客户端
socket.on('user:online', ({ userId }) => { ... });
socket.on('user:offline', ({ userId, lastSeen }) => { ... });
```

---

## ✅ 前端功能已完成清单

### 群聊功能
- [x] 群组创建向导 (两步式)
- [x] 群组列表展示
- [x] 群组成员选择
- [x] 私密群组设置

### 联系人功能
- [x] 联系人搜索 (多条件)
- [x] 联系人添加
- [x] 联系人列表 (在线/离线分组)
- [x] 推荐联系人显示

### 个人资料功能
- [x] 个人资料编辑
- [x] 头像上传/删除
- [x] 个人简介编写
- [x] 隐私设置开关
- [x] 个人资料页面
- [x] 用户统计显示

### 文件分享功能
- [x] 文件上传组件 (FileUpload.vue)
- [x] 拖拽上传支持
- [x] 上传进度显示 (含速度)
- [x] 大文件分片上传 (>5MB)
- [x] 图片消息展示和预览
- [x] 视频消息内嵌播放
- [x] 音频消息内嵌播放
- [x] 文件消息下载
- [x] 消息类型自动识别

### 视频/语音通话功能
- [x] WebRTC 服务封装 (webrtc.js)
- [x] 通话状态管理 (call.js Pinia Store)
- [x] 来电弹窗 (IncomingCallModal.vue)
- [x] 去电弹窗 (OutgoingCallModal.vue)
- [x] 通话进行中界面 (CallView.vue)
- [x] 通话结束界面 (CallEndModal.vue)
- [x] 本地/远程视频显示
- [x] 静音/取消静音
- [x] 开关摄像头
- [x] 前后摄像头切换
- [x] 通话时长计时
- [x] 铃声/回铃音播放
- [x] WebSocket 信令传输
- [x] ICE Candidate 队列机制
- [x] SDP Offer/Answer 交换
- [x] 连接状态监控
- [x] Bug修复: 信令参数错误 (2026-02-07)
- [x] Bug修复: Vue响应式更新问题 (2026-02-07)

### 其他
- [x] 中英文国际化
- [x] 路由配置

---

## 📝 后端开发注意事项

1. **头像上传**: 建议使用云存储(如阿里云OSS、七牛云)保存图片，返回URL
2. **在线状态**: 需要通过WebSocket连接状态判断用户是否在线
3. **隐私设置**: 在返回用户信息时需要根据隐私设置过滤字段
4. **群组成员数**: 建议使用触发器或应用程序维护member_count字段
5. **搜索优化**: 用户搜索建议添加Elasticsearch或在数据库添加全文索引
6. **消息统计**: 可以使用定时任务或缓存来维护消息数量统计

---

*文档更新时间: 2026-02-07*
*前端框架: Vue 3 + Element Plus + Pinia*
*后端框架: Spring Boot + MySQL*
*实时通信: WebSocket (STOMP) + WebRTC*
