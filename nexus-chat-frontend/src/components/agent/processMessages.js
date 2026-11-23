// T6-only labels. Reuses Vue i18n; no new locale store or page namespace.
export const processMessages = {
  zh: {
    stage: '像素工作区', reused: '复用结果', more: '另有 {n} 个已完成步骤', earlierAttempts: '更早尝试的 {n} 个节点见下方详情', handoff: '收到 {from} 的结果',
    thought: '思考过程', copy: '复制思考流', conclusion: '仅看结论', graph: '依赖图', detail: '节点详情', board: '执行看板', showBoard: '展开执行看板', hideBoard: '收起执行看板',
    task: '任务请求', scope: '对象与范围', scopeDetail: '当轮对象与授权范围', unreported: '未上报', recorded: '已记录 {n} 个节点', roles: '已上报 {n} 个角色', calls: '模型调用记录 {n}', tools: '工具调用记录 {n}', reportedCalls: '后端模型调用统计',
    elapsed: '耗时', eventElapsed: '后端最后上报偏移', wallElapsed: '后端墙钟耗时', elapsedNote: '节点耗时与当轮墙钟/事件偏移口径不同；不使用浏览器时钟续算。',
    role: '角色', model: '模型', provider: '供应商', server: 'MCP 服务', node: '节点 ID', run: '运行 ID', attempt: '尝试 ID', call: '调用 ID', phase: '节点类型', duration: '节点上报耗时',
    inputTokens: '输入 Tokens', outputTokens: '输出 Tokens', totalTokens: '总 Tokens', usage: '模型使用量', usageNote: '仅展示上报字段；缺少统计范围时，不视为整轮计费总量。',
    parents: '上报依赖', unresolvedParents: '未解析的父节点', layer: '依赖层 {n}', cyclic: '依赖无法分层', graphNote: '仅按上报且可解析的依赖分层；同层不代表已实际同时执行。',
    emptyNodes: '暂无已上报的执行节点。', emptyArtifacts: '暂无产物', emptySources: '来源未上报', artifacts: '产物', sources: '来源关联', args: '工具参数', reasoning: '模型原生推理', reasoningNote: '这是模型原生推理，不是执行审计；没有上报时不补写。', output: '节点输出', error: '错误信息',
    evidenceNote: '来源为上游关联，不等同已引用或已核查；未关联到节点的记录单独列出。', unassigned: '未归属到节点的记录', preview: '预览产物', identityNote: '稳定身份未完整上报；此卡片仅使用本次展示键，不承诺可恢复。',
    cancelPending: '本地已断流，后端取消未确认。', interruptedNote: '连接中断不表示子节点已停止；以后台后续真实状态为准。', stateBasis: '节点终态未上报；当前展示由当轮终态收束。', lastState: '最后上报状态',
    requestModel: '当前请求模型设置', noWebgl: '3D 舞台暂不可用', copied: '已复制', copyFailed: '复制失败', pipeline: '真实执行节点',
    artifactRelation: { node_association: '上报的节点关联', call_association: '关联调用证据', reported_association: '节点上报关联' },
    state: { idle: '暂无运行', queued: '排队中', running: '运行中', waiting_tool: '等待工具', waiting_approval: '等待审批', stop_requested: '停止待确认', succeeded: '已完成', partial: '部分完成', failed: '失败', cancelled: '已取消', unknown: '状态未上报', interrupted: '连接中断', limited: '受限结束', skipped: '已跳过' },
    kind: { model: '模型调用', task: '任务节点', tool: '工具调用', tools: '工具准备', context: '上下文准备', knowledge: '知识检索', web_search: '网页检索', attachments: '附件处理', collaboration: '协同节点', validate_output: '结果校验', mock: '上报的测试模式节点' }
  },
  en: {
    stage: 'Pixel workspace', reused: 'Reused result', more: '{n} more finished steps', earlierAttempts: '{n} nodes from earlier attempts are in the details below', handoff: 'Received results from {from}',
    thought: 'Thought process', copy: 'Copy trace', conclusion: 'Conclusion only', graph: 'Dependency graph', detail: 'Node details', board: 'Execution board', showBoard: 'Expand execution board', hideBoard: 'Collapse execution board',
    task: 'Task request', scope: 'Objects and scope', scopeDetail: 'Reported objects and authorization scope', unreported: 'Not reported', recorded: '{n} recorded nodes', roles: '{n} reported roles', calls: '{n} model call records', tools: '{n} tool call records', reportedCalls: 'Backend model call count',
    elapsed: 'Elapsed', eventElapsed: 'Last backend elapsed offset', wallElapsed: 'Backend wall time', elapsedNote: 'Node duration and run wall time / event offsets have different scopes; no browser clock extrapolation.',
    role: 'Role', model: 'Model', provider: 'Provider', server: 'MCP server', node: 'Node ID', run: 'Run ID', attempt: 'Attempt ID', call: 'Call ID', phase: 'Node type', duration: 'Reported node duration',
    inputTokens: 'Input tokens', outputTokens: 'Output tokens', totalTokens: 'Total tokens', usage: 'Model usage', usageNote: 'Only reported fields are shown. Without a reported scope, these are not treated as whole-run billing totals.',
    parents: 'Reported dependencies', unresolvedParents: 'Unresolved parent nodes', layer: 'Dependency layer {n}', cyclic: 'Dependencies cannot be layered', graphNote: 'Layers use only reported, resolved dependencies; a shared layer does not prove concurrent execution.',
    emptyNodes: 'No execution nodes have been reported.', emptyArtifacts: 'No artifacts yet', emptySources: 'Sources not reported', artifacts: 'Artifacts', sources: 'Linked sources', args: 'Tool arguments', reasoning: 'Model-native reasoning', reasoningNote: 'Model-native reasoning is not an execution audit. Missing reasoning is never filled in.', output: 'Node output', error: 'Error information',
    evidenceNote: 'Upstream links are not proof of citation or verification. Records without node attribution are listed separately.', unassigned: 'Records without node attribution', preview: 'Preview artifact', identityNote: 'Stable identity is incomplete. This card uses a display-only key, not a recovery identifier.',
    cancelPending: 'The local stream closed; backend cancellation is not confirmed.', interruptedNote: 'A lost connection does not mean child nodes stopped. Later backend reports remain authoritative.', stateBasis: 'The node terminal state was not reported; display is constrained by the run terminal state.', lastState: 'Last reported state',
    requestModel: 'Current request model setting', noWebgl: 'The 3D stage is unavailable', copied: 'Copied', copyFailed: 'Copy failed', pipeline: 'Reported execution nodes',
    artifactRelation: { node_association: 'Reported node association', call_association: 'Related call evidence', reported_association: 'Node-reported link' },
    state: { idle: 'No run', queued: 'Queued', running: 'Running', waiting_tool: 'Waiting for tool', waiting_approval: 'Waiting for approval', stop_requested: 'Stop unconfirmed', succeeded: 'Completed', partial: 'Partially completed', failed: 'Failed', cancelled: 'Cancelled', unknown: 'State not reported', interrupted: 'Connection interrupted', limited: 'Ended with limits', skipped: 'Skipped' },
    kind: { model: 'Model call', task: 'Task node', tool: 'Tool call', tools: 'Tool preparation', context: 'Context preparation', knowledge: 'Knowledge retrieval', web_search: 'Web retrieval', attachments: 'Attachment processing', collaboration: 'Collaboration node', validate_output: 'Output validation', mock: 'Reported test-mode node' }
  }
}
