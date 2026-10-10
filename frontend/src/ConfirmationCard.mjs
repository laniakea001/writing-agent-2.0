import React from 'react';
const h=React.createElement;
const titles={CONFIRM_PLAN:'确认写作计划',COMPLETE_ARTICLE:'确认全文完成',REOPEN_ARTICLE:'重新打开文章',CONFIRM_CHAPTER:'确认章节修改候选'};
function documentText(node){
 if(node?.type==='text')return node.text||'';
 if(node?.type==='hardBreak')return '\n';
 return (node?.content||[]).map(documentText).join(['paragraph','heading','codeBlock'].includes(node?.type)?'':'\n');
}
// The same card is used in chat and manual dialogs. Server eligibility is NOT an authorization token.
export function ConfirmationCard({card,onDecision,onLookup,submitting=false,decisionPending=false,unavailable=false}){
 const ready=!unavailable&&!submitting&&!decisionPending&&card.status==='OPEN';
 const allowed=Array.isArray(card.allowedDecisions)?card.allowedDecisions:[];
 const reason=submitting?'正在提交这张卡的决定…':decisionPending?'上次决定结果尚未核实，请查询原决定结果，不要再次提交。':unavailable?'暂时无法核验卡片状态，请刷新状态。':card.disabledReasonText||(!Array.isArray(card.allowedDecisions)?'服务版本尚不支持确认状态核验，请更新后端后刷新。':null);
 const receipt=card.receipt;
 return h('section',{className:'hitl-confirmation', 'data-proposal-id':card.proposalId,'data-effective-status':card.status},
  h('h4',null,titles[card.type]||'人工确认'),
  h('p',{className:'hitl-target'},card.targetLabel||'当前文章'),
  h('p',null,card.effectDescription||card.displaySummary||'请核对本次操作。'),
  card.continuation&&h('div',{className:'hitl-continuation'},h('p',null,'后续目标：'+(Array.isArray(card.continuation.targets)?card.continuation.targets.map(t=>'第'+t.chapterIndex+'章').join('、'):'第'+card.continuation.chapterIndex+'章')),h('p',null,'修改意见：'+card.continuation.instruction)),
  card.candidate&&h('div',{className:'hitl-candidate'},h('strong',null,'第'+card.candidate.chapterIndex+'章 · '+card.candidate.title),h('p',null,'修改意见：'+card.candidate.instruction),h('p',{className:'muted'},card.status==='APPROVED'?'该候选已确认写入，请查看下方固定回执。':card.status==='REJECTED'?'该候选已拒绝，没有通过本卡写入正文。':card.status==='OPEN'?'以下是候选，尚未写入正文；拒绝会保留原文。':'该候选已失效，不能通过本卡写入正文。'),h('pre',{style:{whiteSpace:'pre-wrap',maxHeight:'260px',overflowY:'auto'}},card.candidate.contentJson?documentText(card.candidate.contentJson):card.candidate.bodyMarkdown)),
  h('p',null,'状态：',card.statusLabel||card.status),
  h('p',{className:'muted'},'项目版本 ',String(card.expectedProjectVersion??'—'),' · 文章版本 ',String(card.expectedArticleVersion??'—')),
  card.expiresAt&&h('p',{className:'muted'},'有效至 ',new Date(card.expiresAt).toLocaleString('zh-CN')),
  reason&&h('p',{'data-disabled-reason':true,className:'hitl-reason',role:'status'},reason),
  h('div',{className:'hitl-actions'},
   h('button',{type:'button',disabled:!ready||!allowed.includes('APPROVE'),onClick:()=>onDecision(card,'APPROVE')},card.type==='CONFIRM_CHAPTER'?'确认写入':'确认'),
   h('button',{type:'button',disabled:!ready||!allowed.includes('REJECT'),onClick:()=>onDecision(card,'REJECT')},'拒绝'),
   decisionPending&&h('button',{type:'button',disabled:submitting,onClick:()=>onLookup(card)},'查询原决定结果')),
  receipt&&h('div',{className:'hitl-receipt'},h('strong',null,'固定回执'),h('p',null,'结果：',receipt.outcomeCode),h('p',null,'操作：',String(receipt.operationId||receipt.actionId||'')),h('p',null,'文章版本：',String(receipt.articleVersion??'—')),
   h('p',null,receipt.actionStatus==='FAILED'?'本卡未提交业务改变。':'以该操作的固定结果为准，不是最新正文快照。')),
  h('details',null,h('summary',null,'确认身份'),h('p',null,card.proposalId)));
}
