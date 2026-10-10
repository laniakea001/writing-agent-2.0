import test from 'node:test';
import assert from 'node:assert/strict';
import {subscribeExecution} from './executionStream.mjs';
test('chat and task viewers share one read-only connection and cleanup the final viewer',()=>{
  let opens=0,closes=0;const handlers={};
  class Source{constructor(url){opens++;assert.equal(url,'/api/writing/v2/runs/root-test/events');}addEventListener(name,fn){handlers[name]=fn;}close(){closes++;}}
  let first=0,second=0;
  const a=subscribeExecution('root-test',{fact:()=>first++},Source);
  const b=subscribeExecution('root-test',{fact:()=>second++},Source);
  assert.equal(opens,1);
  handlers['execution-event']({data:'{"outputType":"MODEL_STARTED"}'});
  assert.equal(first,1);assert.equal(second,1);
  a();assert.equal(closes,0);
  b();assert.equal(closes,1);
});
