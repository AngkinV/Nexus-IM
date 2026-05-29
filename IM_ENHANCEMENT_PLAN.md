# IM Enhancement Plan

## Goal

Expand Nexus Chat from basic realtime messaging into a fuller IM system while keeping the current Java backend as the source of truth and preserving compatibility with existing Web/Electron/Flutter clients.

## Phase 1: Core Message Capabilities

Status: in progress.

- Message edit: only the sender can edit non-recalled text/emoji messages.
- Message recall: only the sender can recall; recall clears content/file URL and broadcasts `MESSAGE_RECALLED`.
- Reply references: `messages.reply_to_message_id` stores quoted-message linkage.
- Reactions: `message_reactions` stores per-user emoji reactions with aggregate counts in `MessageDTO`.
- Basic search: `/api/messages/chat/{chatId}/search?query=...` searches message text inside a chat.
- Delivery state: `message_delivery_status` tracks pending/delivered per recipient.

## Phase 2: Reliable Server Outbox

- Replace Redis-only offline queues with a durable `message_outbox` table.
- Keep Redis as a fast notification path, but recover undelivered rows after restart.
- Add delivery ACK from clients, separate from "server attempted delivery".
- Add gap sync by `(chatId, sequenceNumber)` for multi-device reconciliation.

## Phase 3: Multi-Device Sync

- Add per-device cursors: user, deviceId, chatId, lastSequenceNumber.
- Delta sync should include edits, recalls, reactions, delivery/read changes, and deleted/hid state.
- Resolve optimistic client messages by `clientMsgId` across all devices.

## Phase 4: Push Notifications

- Web Push: store browser push subscriptions per user/device.
- Mobile push: store APNs/FCM tokens per user/device.
- Dispatch push from the same outbox pipeline used for realtime delivery.
- Keep message preview policy configurable per chat/user privacy setting.

## Phase 5: Group Collaboration

- Group announcements: persistent announcement entity, admin-only write by default.
- Invite links: tokenized join links with expiry, usage limits, and role policy.
- Group permissions: message sending, invite management, announcement management, member management.
- Audit log for group admin operations.

## Phase 6: Search

- Current Phase 1 search is SQL LIKE for low-risk rollout.
- Upgrade path: MySQL FULLTEXT or external search service once message volume grows.
- Search scope should support per-chat, all chats, sender filter, file type filter, and date range.
