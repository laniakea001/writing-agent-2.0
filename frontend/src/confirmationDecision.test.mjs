import test from 'node:test';
import assert from 'node:assert/strict';
import {createConfirmationDecision} from './confirmationDecision.mjs';
function store(){const data=new Map();return {getItem:k=>data.get(k)||null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k),get length(){return data.size;},key:i=>Array.from(data.keys())[i]};}
const card={proposalId:'p',workspaceId:'w',projectId:'article'};
test('lost response preserves the same decision key and blocks the opposite decision',async()=>{
 const storage=store();let posts=0;const client=createConfirmationDecision({storage,key:()=> 'stable',request:async()=>{posts++;throw Error('network');}});
 await assert.rejects(client.submit(card,'APPROVE'));
 assert.equal(client.pending(card).clientActionId,'stable');
 assert.equal(client.pendingForProject('article').proposalId,'p');
 await assert.rejects(client.submit(card,'REJECT'),/DECISION_RESULT_UNKNOWN/);assert.equal(posts,1);
 const recovered=createConfirmationDecision({storage,request:async path=>{assert.match(path,/clientActionId=stable/);return {actionStatus:'SUCCEEDED',actionId:'stable',outcomeCode:'PLAN_CONFIRMED'};}});
 assert.equal((await recovered.lookup(card)).outcomeCode,'PLAN_CONFIRMED');assert.equal(recovered.pending(card),null);
});
test('definitive rejection clears pending; failed lookup does not',async()=>{
 const storage=store();const client=createConfirmationDecision({storage,key:()=> 'k',request:async()=>{throw Object.assign(Error('STALE'),{status:409});}});
 await assert.rejects(client.submit(card,'APPROVE'));assert.equal(client.pending(card),null);
 storage.setItem('wa2-confirmation-pending:p',JSON.stringify({clientActionId:'k',decision:'APPROVE'}));
 await assert.rejects(client.lookup(card));assert.equal(client.pending(card).clientActionId,'k');
});
