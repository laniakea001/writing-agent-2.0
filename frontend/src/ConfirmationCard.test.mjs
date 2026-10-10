import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {ConfirmationCard} from './ConfirmationCard.mjs';
const open={proposalId:'p1',projectId:'article-1',type:'COMPLETE_ARTICLE',status:'OPEN',statusLabel:'待你确认',targetLabel:'企业知识助手',allowedDecisions:['APPROVE','REJECT'],effectDescription:'完成后正文只读。',expiresAt:'2099-10-09T07:59:26Z',expectedArticleVersion:30};
test('a batch reopen card displays every target rather than an undefined single chapter',()=>{
 const html=renderToStaticMarkup(React.createElement(ConfirmationCard,{card:{...open,type:'REOPEN_ARTICLE',continuation:{batchId:'b1',targets:[{chapterIndex:1},{chapterIndex:2},{chapterIndex:3}],instruction:'全部润色'}},onDecision:()=>{},onLookup:()=>{}}));
 assert.match(html,/第1章、第2章、第3章/);assert.doesNotMatch(html,/undefined/);assert.match(html,/全部润色/);
});
test('a chapter candidate shows the exact chapter, instruction and prose before confirming',()=>{
 const html=renderToStaticMarkup(React.createElement(ConfirmationCard,{card:{...open,type:'CONFIRM_CHAPTER',candidate:{chapterIndex:19,title:'目标章',instruction:'大概100字，结尾xxs',bodyMarkdown:'待确认的新正文 xxs'}},onDecision:()=>{},onLookup:()=>{}}));
 assert.match(html,/第19章/);assert.match(html,/待确认的新正文 xxs/);assert.match(html,/大概100字，结尾xxs/);assert.match(html,/确认写入/);
});
test('an authoritative open card renders a clear target and enabled decisions',()=>{
 const html=renderToStaticMarkup(React.createElement(ConfirmationCard,{card:open,onDecision:()=>{},onLookup:()=>{}}));
 assert.match(html,/企业知识助手/);assert.match(html,/待你确认/);assert.match(html,/完成后正文只读/);assert.doesNotMatch(html,/disabled=""/);
});
test('an expired card explains the reason rather than offering unexplained grey buttons',()=>{
 const html=renderToStaticMarkup(React.createElement(ConfirmationCard,{card:{...open,status:'EXPIRED',allowedDecisions:[],statusLabel:'已过期',disabledReasonText:'确认卡已过期，不能批准。'},onDecision:()=>{},onLookup:()=>{}}));
 assert.match(html,/确认卡已过期，不能批准/);assert.match(html,/data-disabled-reason/);assert.match(html,/disabled=""/);
});
test('a card with an uncertain decision cannot issue another decision',()=>{
 const html=renderToStaticMarkup(React.createElement(ConfirmationCard,{card:open,decisionPending:true,onDecision:()=>{},onLookup:()=>{}}));
 assert.match(html,/查询原决定结果/);assert.match(html,/disabled=""/);
});
