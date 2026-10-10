import test from 'node:test';
import assert from 'node:assert/strict';
import {createElement} from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {RunTimeline} from './RunTimeline.mjs';
test('shared timeline renders observable tool events, not raw prompt data',()=>{
 const html=renderToStaticMarkup(createElement(RunTimeline,{status:'RUNNING',facts:[{sourceRunId:'r1',sequence:1,outputType:'TOOL_CALL_STARTED',safePayload:{toolName:'rewrite_chapters',prompt:'PRIVATE_NOT_FOR_DISPLAY'}}]}));
 assert.match(html,/正在调用工具/);assert.match(html,/rewrite_chapters/);assert.doesNotMatch(html,/PRIVATE_NOT_FOR_DISPLAY/);assert.match(html,/run-timeline-scroll/);
});
