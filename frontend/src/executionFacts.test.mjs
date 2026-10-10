import {test} from 'node:test';
import assert from 'node:assert/strict';
import {appendExecutionFact} from './executionFacts.mjs';
test('replayed event is displayed once and separate source sequences remain distinct',()=>{
    const a={sourceRunId:'root',sequence:1,outputType:'MODEL_STARTED'};
    const b={sourceRunId:'child',sequence:1,outputType:'TOOL_CALL_STARTED'};
    const initial=appendExecutionFact([],a);
    assert.equal(appendExecutionFact(initial,a).length,1);
    assert.equal(appendExecutionFact(initial,b).length,2);
});
