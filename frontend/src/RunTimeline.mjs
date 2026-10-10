import {createElement as h} from 'react';
const labels={RUN_STARTED:'开始执行',MODEL_STARTED:'模型处理中',MODEL_FINISHED:'模型返回',TOOL_CALL_STARTED:'正在调用工具',TOOL_CALL:'工具参数已确定',TOOL_FINISHED:'工具调用返回',TOOL_BUSINESS_FAILED:'工具业务操作失败',CHILD_RUN_STARTED:'阶段 Agent 开始执行',ARTIFACT_SAVED:'稿件已保存',CONFIRMATION_REQUIRED:'等待人工确认',RUN_FINISHED:'任务结束',RUN_TERMINAL:'任务结束',BATCH_ACCEPTED:'逐章批次已登记'};
/** @param {{facts?: Array<{sourceRunId:string, sequence:number, outputType:string, safePayload?:Record<string,unknown>}>, status:string, compact?:boolean}} props */
export function RunTimeline({facts=[],status,compact=false}){
 return h('details',{className:'run-timeline',open:!compact||['QUEUED','RUNNING'].includes(status)},
  h('summary',null,'执行过程 · '+status),
  h('div',{className:'run-timeline-scroll',role:'log','aria-label':'任务执行过程'},
   facts.filter(f=>labels[f.outputType]).map(f=>h('div',{key:f.sourceRunId+':'+f.sequence,className:'execution-card '+f.outputType.toLowerCase()},h('strong',null,labels[f.outputType]),h('small',null,[f.safePayload?.agentRole,f.safePayload?.toolName,f.safePayload?.outcomeCode].filter(Boolean).map(String).join(' · ')))),
   !facts.length&&h('p',{className:'muted'},'读取持久化执行记录…')));
}
