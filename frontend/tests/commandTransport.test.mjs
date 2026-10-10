import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createCommandTransport,ApiError,pendingStorageKey} from '../src/commandTransport.mjs';

test('batch dispatcher refusal is definitive and explains why no task started',()=>{
  const error=new ApiError(409,'BATCH_DISPATCH_UNAVAILABLE');
  assert.equal(error.code,'BATCH_DISPATCH_UNAVAILABLE');
  assert.match(error.message,/任务未受理/);
});

function fixture(fetchFn){
  const entries=new Map([['wa2-d2-workspace','workspace-1']]);
  const storage={getItem:k=>entries.get(k)??null,setItem:(k,v)=>entries.set(k,v),removeItem:k=>entries.delete(k)};
  return createCommandTransport({storage,fetchFn,timeoutSignal:()=>undefined});
}
const response=(status,value)=>({ok:status>=200&&status<300,status,json:async()=>value});
const command={clientActionId:'original-key',contentJson:{type:'doc'}};

test('explicit retry uses identical request body and original action key, not a fresh command',async()=>{
  const sent=[];const t=fixture(async(path,options)=>{sent.push({path,body:JSON.parse(options.body)});if(sent.length===1)throw new Error('lost');return response(200,{actionStatus:'SUCCEEDED'});});
  await assert.rejects(()=>t.request('/projects/p/chapters/c','PUT',command));
  await t.retryPending();assert.deepEqual(sent[0],sent[1]);assert.equal(t.pending(),null);
});

test('successful save clears pending key only after a readable response',async()=>{
  const t=fixture(async()=>response(200,{actionStatus:'SUCCEEDED'}));
  await t.request('/projects/p/chapters/c','PUT',command);assert.equal(t.pending(),null);
});
test('connection loss retains exact target, body and key and blocks a new write',async()=>{
  let calls=0;const t=fixture(async()=>{calls++;throw new Error('lost response');});
  await assert.rejects(()=>t.request('/projects/p/chapters/c','PUT',command));
  assert.equal(t.pending().projectId,'p');assert.deepEqual(t.pending().body,command);
  await assert.rejects(()=>t.request('/projects/p/chapters/c','PUT',{clientActionId:'new-key'}),e=>e instanceof ApiError&&e.code==='PREVIOUS_RESULT_UNKNOWN');
  assert.equal(calls,1);
});
test('server 500 remains unknown rather than silently freeing the original key',async()=>{
  const t=fixture(async()=>response(500,{code:'INTERNAL_ERROR'}));
  await assert.rejects(()=>t.request('/projects/p/plans','POST',command));assert.equal(t.pending().body.clientActionId,'original-key');
});
test('deterministic version conflict releases pending request, not user content',async()=>{
  const t=fixture(async()=>response(409,{code:'VERSION_CONFLICT'}));
  await assert.rejects(()=>t.request('/projects/p/chapters/c','PUT',command),e=>e.code==='VERSION_CONFLICT');assert.equal(t.pending(),null);assert.equal(command.contentJson.type,'doc');
});
test('lookup read never clears or resubmits a pending write',async()=>{
  let mode='write';const t=fixture(async()=>{if(mode==='write')throw new Error('network');return response(200,{actionStatus:'SUCCEEDED'});});
  await assert.rejects(()=>t.request('/projects/p/plans','POST',command));mode='read';
  await t.request('/actions/lookup');assert.equal(t.pending().body.clientActionId,'original-key');
});
